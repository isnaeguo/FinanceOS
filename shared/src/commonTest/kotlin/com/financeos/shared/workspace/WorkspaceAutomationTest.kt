package com.financeos.shared.workspace

import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class WorkspaceAutomationTest {
    private val automation = WorkspaceAutomation()
    private val start = Instant.parse("2026-10-01T00:00:00Z")
    private val expense = Transaction(
        id = "expense", amount = 1200, type = TransactionType.EXPENSE,
        categoryId = "system-other", accountId = "bank", dateTime = start, note = "CAFE merchant",
    )

    @Test
    fun rulesPreviewOnlyUnclassifiedCompatibleLiveEntriesInTheirOwnBook() {
        val food = WorkspaceRule("rule-1", "book-1", " 咖啡 ", " cafe ", "system-food")
        val state = automation.saveRule(automation.emptyState(), food)
        val rows = listOf(
            expense,
            expense.copy(id = "deleted", deletedAt = 1),
            expense.copy(id = "income", type = TransactionType.INCOME),
            expense.copy(id = "categorized", categoryId = "system-food"),
            expense.copy(id = "different-merchant", note = "bus"),
        )
        assertEquals("cafe", state.rules.single().noteContains)
        assertTrue(automation.ruleSuggestions(state, "book-2", rows, DefaultCategories.all).isEmpty())
        val suggestion = automation.ruleSuggestions(state, "book-1", rows, DefaultCategories.all).single()
        assertEquals(WorkspaceRuleSuggestion(expense, "system-food", "rule-1"), suggestion)
        assertEquals("system-other", suggestion.transaction.categoryId)
        assertTrue(automation.removeRule(state, "rule-1").rules.isEmpty())
    }

    @Test
    fun inboxDetectsCandidatesAndPersistsManualReceiptAndDismissal() {
        val rows = listOf(
            expense,
            expense.copy(id = "nearby", dateTime = start + 1.minutes),
            expense.copy(id = "other-account", dateTime = start + 1.minutes, accountId = "separate"),
            expense.copy(id = "too-late", dateTime = start + 30.minutes),
        )
        val receipt = WorkspaceInboxItem(
            id = "receipt", bookId = "book-1", kind = WorkspaceInboxKind.REIMBURSEMENT, title = "待报销发票",
            note = "OCR 识别", transactionIds = listOf(expense.id), attachmentReference = "local-file-1.pdf",
        )
        val state = automation.saveItem(automation.emptyState(), receipt)
        val inbox = automation.reviewItems(state, "book-1", rows)
        assertEquals(receipt, inbox.first())
        val signal = inbox.single { it.kind == WorkspaceInboxKind.DUPLICATE }
        assertEquals(listOf("nearby", "expense"), signal.transactionIds)
        assertFalse(automation.reviewItems(state, "book-2", rows).any { it.id == receipt.id })
        val completed = automation.completeItem(automation.completeItem(state, signal.id), receipt.id)
        val loaded = Json.decodeFromString<WorkspaceAutomationState>(Json.encodeToString(completed))
        assertTrue(loaded.items.single().completed)
        assertTrue(signal.id in loaded.dismissedSignals)
        assertFalse(automation.reviewItems(loaded, "book-1", rows.reversed()).any { it.id == signal.id || it.id == receipt.id })
        assertFailsWith<IllegalArgumentException> { receipt.copy(attachmentReference = "../outside.pdf") }
    }

    @Test
    fun recurringPriceChangeRequiresTwoComparablePriorPayments() {
        val earlier = expense.copy(id = "earliest", note = "订阅费用")
        val previous = earlier.copy(id = "prior", dateTime = start + 30.days)
        val current = previous.copy(id = "current", dateTime = start + 60.days, amount = 1500)
        fun priceSignals(rows: List<Transaction>) = automation.reviewItems(automation.emptyState(), null, rows)
            .filter { it.kind == WorkspaceInboxKind.PRICE_INCREASE }
        assertTrue(priceSignals(listOf(current, previous)).isEmpty())
        assertTrue(priceSignals(listOf(current, previous, earlier.copy(amount = 1300))).isEmpty())
        val signal = priceSignals(listOf(previous, current, earlier)).single()
        assertEquals(listOf("current", "prior", "earliest"), signal.transactionIds)
        assertEquals(1500L, signal.amountMinor)
    }
}

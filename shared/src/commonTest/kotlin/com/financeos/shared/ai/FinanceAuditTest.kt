package com.financeos.shared.ai

import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class FinanceAuditTest {
    private val start = Instant.parse("2026-10-01T00:00:00Z")
    private val end = Instant.parse("2026-11-01T00:00:00Z")
    private val expense = Transaction(
        id = "private-transaction-id",
        amount = 1234,
        type = TransactionType.EXPENSE,
        categoryId = "system-food",
        accountId = "private-account-id",
        dateTime = start,
        note = "隐私店铺 receipt@example.com",
    )

    private fun answer(question: String, rows: List<Transaction>) = FinanceAudit().answer(
        question, rows, DefaultCategories.all, start.toEpochMilliseconds(), end.toEpochMilliseconds(),
    )

    @Test
    fun merchantTotalsUseExactMoneyAndOnlyLiveRowsInTheSelectedDates() {
        val selected = answer("在「隐私店铺」花了多少钱？", listOf(
            expense,
            expense.copy(id = "excluded-other", note = "另一家"),
            expense.copy(id = "excluded-deleted", deletedAt = 1),
            expense.copy(id = "excluded-end", dateTime = end),
            expense.copy(id = "excluded-earlier", dateTime = Instant.parse("2026-09-30T23:59:59Z")),
        ))
        assertEquals(1, selected.matchedCount)
        assertEquals(listOf(expense), selected.sources.map { it.transaction })
        assertEquals("支出 ¥12.34", selected.facts[1].text)
        assertEquals(listOf("S1"), selected.facts[1].sourceReferences)
    }

    @Test
    fun comparisonPreservesEarlierSourcesWhenCurrentPeriodExceedsEvidenceCap() {
        val current = List(40) { i -> expense.copy(id = "current-$i", type = TransactionType.INCOME, amount = 100) }
        val previous = expense.copy(id = "previous", type = TransactionType.INCOME, amount = 1200,
            dateTime = Instant.parse("2026-09-20T00:00:00Z"))
        val result = answer("收入变化", current + previous + expense)
        assertEquals(41, result.matchedCount)
        assertEquals(11, result.omittedSourceCount)
        assertEquals(30, result.sources.size)
        assertEquals("前一等长期间收入 ¥12.00", result.facts[1].text)
        assertEquals("收入差额 ¥28.00", result.facts[2].text)
        val source = result.sources.single { it.transaction == previous }.reference
        assertContains(result.facts[1].sourceReferences, source)
    }

    @Test
    fun categoryRankingAggregatesEntriesBeforeSorting() {
        val result = answer("支出最多的是哪个分类？", listOf(
            expense,
            expense.copy(id = "second-food"),
            expense.copy(id = "largest-single", amount = 1600, categoryId = "system-other"),
        ))
        assertEquals("金额最高的分类", result.title)
        assertContains(result.facts.first().text, "¥24.68")
        assertEquals(2, result.facts.first().sourceReferences.size)
    }

    @Test
    fun compatibilityProtocolsSendOnlyVerifiedValuesAndIgnoreModelFreeText() {
        val answer = answer("在「隐私店铺」花了多少钱？", listOf(expense))
        val valid = """{"factIds":["F2"],"followUpId":"compare","text":"invented amount"}"""
        val unknown = """{"factIds":["F99"],"followUpId":"compare"}"""
        for (protocol in AiApiProtocol.entries) {
            val request = FinanceAi(protocol).auditRequest("test-model", answer)
            assertContains(request.body, "1234")
            for (privateData in listOf("隐私店铺", "receipt@example.com", expense.id, expense.accountId.orEmpty(), "S1")) {
                assertFalse(request.body.contains(privateData), privateData)
            }
            val narrative = request.parse(envelope(protocol, valid))
            assertEquals(listOf(answer.facts[1]), narrative.highlights)
            assertTrue(narrative.followUp.isNotBlank())
            assertFailsWith<IllegalArgumentException> { request.parse(envelope(protocol, unknown)) }
        }
    }

    @Test
    fun unsupportedQuestionsFailWithoutCallingProvider() {
        assertFailsWith<IllegalArgumentException> { answer("写一首诗", listOf(expense)) }
    }

    private fun envelope(protocol: AiApiProtocol, value: String): String = when (protocol) {
        AiApiProtocol.RESPONSES -> """{"status":"completed","output_text":${JsonPrimitive(value)}}"""
        AiApiProtocol.CHAT_COMPLETIONS ->
            """{"choices":[{"message":{"content":${JsonPrimitive(value)}},"finish_reason":"stop"}]}"""
    }
}

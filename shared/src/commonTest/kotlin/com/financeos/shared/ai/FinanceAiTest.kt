package com.financeos.shared.ai

import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive

class FinanceAiTest {
    private val ai = FinanceAi()
    private val start = Instant.parse("2026-10-01T00:00:00Z")
    private val month = BudgetMonth(2026, 10)
    private val period = MonthPeriod(month, start, Instant.parse("2026-11-01T00:00:00Z"))
    private val expense = Transaction(
        "private-id", 1250, TransactionType.EXPENSE, "system-other", "private-account", start,
        "午餐 test@example.com +86 13612345678 https://example.com/receipt", updatedAt = 14,
    )
    private val income = expense.copy(
        id = "z-income-id", amount = 5500, type = TransactionType.INCOME, note = "收入备注",
    )

    @Test
    fun classificationSendsRedactedDataAndRejectsWrongTypeAndDuplicateRows() {
        val plan = ai.classificationRequest("a-model", listOf(expense, income), DefaultCategories.all)
        assertEquals(2, plan.transactionCount)
        assertContains(plan.body, "\"instructions\"")
        assertContains(plan.body, "\"input\"")
        assertContains(plan.body, "\"store\":false")
        assertFalse(plan.body.contains("\"messages\""))
        assertContains(plan.body, "午餐")
        assertContains(plan.body, "[号码]")
        for (sensitive in listOf(expense.id, "private-account", "13612345678", "example.com", "1250", "2026-10")) {
            assertFalse(plan.body.contains(sensitive), sensitive)
        }
        val valid = plan.parse(envelope("""{"suggestions":[{"row":"r1","category":"c1","reason":"匹配餐饮"}]}"""))
        assertEquals(AiCategorySuggestion(expense.id, "system-other", 14, "system-food", "匹配餐饮"), valid.single())
        assertFailsWith<IllegalArgumentException> {
            plan.parse(envelope("""{"suggestions":[{"row":"r2","category":"c1"}]}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            plan.parse(envelope("""{"suggestions":[{"row":"r1","category":"c1"},{"row":"r1","category":"c2"}]}"""))
        }
    }

    @Test
    fun classificationBatchesKeepAliasesLocalAndRespectTheSelectedLimit() {
        val transactions = (0 until 205).map { index ->
            expense.copy(id = "private-${index.toString().padStart(3, '0')}")
        }.reversed() + listOf(
            expense.copy(id = "blank", note = " "),
            expense.copy(id = "deleted", deletedAt = 1),
            expense.copy(id = "already-classified", categoryId = "system-food"),
            expense.copy(id = "private-020"),
        )
        val plans = ai.classificationRequests("a-model", transactions, DefaultCategories.all)
        assertEquals(List(10) { 20 }, plans.map { it.transactionCount })
        val firstOfEach = envelope("""{"suggestions":[{"row":"r1","category":"c1"}]}""")
        plans.forEachIndexed { index, plan ->
            assertEquals("private-${(index * 20).toString().padStart(3, '0')}", plan.parse(firstOfEach).single().transactionId)
            assertFalse(plan.body.contains("private-"))
        }
        val short = ai.classificationRequests("a-model", transactions.take(21), DefaultCategories.all)
        assertEquals(listOf(20, 1), short.map { it.transactionCount })
    }

    @Test
    fun monthlyRequestUsesOnlyLiveInPeriodAggregatesAndRejectsInvalidResponse() {
        val body = ai.spendingRequest(
            "a-model",
            listOf(expense, income, expense.copy(id = "deleted", deletedAt = 1), expense.copy(id = "end", dateTime = period.endExclusive)),
            DefaultCategories.all,
            period,
            listOf(Budget("month-budget", month, 2000)),
        )
        for (value in listOf("1250", "5500", "2000")) assertContains(body, value)
        for (sensitive in listOf("午餐", "收入备注", "private-id", "month-budget")) assertFalse(body.contains(sensitive))
        assertEquals(
            AiSpendingAdvice("消费汇总", listOf("核对待分类流水")),
            ai.parseSpending(envelope("""{"summary":"消费汇总","recommendations":["核对待分类流水"]}""")),
        )
        assertFailsWith<IllegalArgumentException> { ai.parseSpending("""{"error":"rate limited"}""") }
        assertFailsWith<IllegalArgumentException> { ai.parseSpending(envelope("""{"summary":[],"recommendations":[]}""")) }
    }

    @Test
    fun responsesReadsAssistantTextPartsInOrderAfterReasoning() {
        val response = """
            {"object":"response","status":"completed","output":[
              {"type":"reasoning","summary":[{"type":"summary_text","text":"内部摘要"}]},
              {"type":"message","role":"assistant","content":[
                {"type":"output_text","text":"{\"summary\":\"消费 "},
                {"type":"output_text","text":"汇总\",\"recommendations\":[\"核对流水\"]}"}
              ]}
            ]}
        """.trimIndent()
        assertEquals(AiSpendingAdvice("消费 汇总", listOf("核对流水")), ai.parseSpending(response))

        val simplified = """{"output_text":${JsonPrimitive("""{"summary":"汇总","recommendations":["核对流水"]}""")}}"""
        assertEquals("汇总", ai.parseSpending(simplified).summary)
    }

    @Test
    fun incompleteResponsesAndRefusalsNeverYieldAdvice() {
        val partial = """{"status":"incomplete","output_text":${JsonPrimitive("""{"summary":"汇总","recommendations":["核对流水"]}""")}}"""
        val refused = """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"blocked"}]}]}"""
        for (response in listOf(partial, refused)) {
            assertFailsWith<IllegalArgumentException> { ai.parseSpending(response) }
        }
    }

    @Test
    fun explicitChatConfigurationKeepsPreviousRequestShape() {
        val chat = FinanceAi(AiApiProtocol.CHAT_COMPLETIONS)
        val request = chat.classificationRequest("test/model", listOf(expense), DefaultCategories.all)
        assertContains(request.body, "\"messages\"")
        assertTrue(request.parse(chatEnvelope("""{"suggestions":[]}""")).isEmpty())
    }

    @Test
    fun generalConversationSendsOnlyRecentExplicitMessagesAndReadsText() {
        val typedMessages = (1..8).flatMap { turn -> listOf(
            FinanceConversationMessage(FinanceConversationRole.USER, "question-$turn"),
            FinanceConversationMessage(FinanceConversationRole.ASSISTANT, "answer-$turn"),
        ) } + FinanceConversationMessage(FinanceConversationRole.USER, "如何按周安排预算？")
        val responses = ai.conversationRequest("model", typedMessages)
        assertContains(responses.body, "\"input\":[")
        assertContains(responses.body, "\"store\":false")
        assertContains(responses.body, "\"role\":\"assistant\"")
        assertContains(responses.body, "question-4")
        assertContains(responses.body, "如何按周安排预算？")
        assertFalse(responses.body.contains("question-3"))
        assertFalse(responses.body.contains("private-account"))
        assertEquals("每周先预留固定开支。", responses.parse(envelope("每周先预留固定开支。")))
        val legacy = FinanceAi(AiApiProtocol.CHAT_COMPLETIONS).conversationRequest("model", typedMessages.takeLast(3))
        assertContains(legacy.body, "\"messages\":[{\"role\":\"system\"")
        assertFalse(legacy.body.contains("\"input\""))
        assertEquals("用分组预算。", legacy.parse(chatEnvelope("用分组预算。")))
    }

    @Test
    fun generalConversationRejectsInvalidTurnOrderAndPartialReplies() {
        val user = FinanceConversationMessage(FinanceConversationRole.USER, "预算")
        val assistant = FinanceConversationMessage(FinanceConversationRole.ASSISTANT, "按周拆分。")
        for (invalid in listOf(emptyList(), listOf(assistant), listOf(user, user, user), listOf(user, assistant))) {
            assertFailsWith<IllegalArgumentException> { ai.conversationRequest("model", invalid) }
        }
        val draft = ai.conversationRequest("model", listOf(user))
        assertFailsWith<IllegalArgumentException> { draft.parse("""{"status":"incomplete","output_text":"partial"}""") }
        assertEquals(5_999, draft.parse(envelope("x".repeat(5_999) + "😀")).length)
    }

    private fun envelope(content: String): String =
        """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":${JsonPrimitive(content)}}]}]}"""

    private fun chatEnvelope(content: String): String =
        """{"choices":[{"message":{"content":${JsonPrimitive(content)}},"finish_reason":"stop"}]}"""
}

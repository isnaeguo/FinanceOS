package com.financeos.shared.ai

import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant

class FinanceChatTest {
    private val periods = FinanceChatPeriods(
        currentStartEpochMillis = instant("2026-10-01").toEpochMilliseconds(),
        currentEndEpochMillis = instant("2026-11-01").toEpochMilliseconds(),
        previousStartEpochMillis = instant("2026-09-01").toEpochMilliseconds(),
        previousEndEpochMillis = instant("2026-10-01").toEpochMilliseconds(),
    )
    private val rows = listOf(
        entry("food-current", "2026-10-05", 2_000, "system-food"),
        entry("transit-current", "2026-10-12", 3_500, "system-transport"),
        entry("food-prior", "2026-09-02", 500, "system-food"),
        entry("transit-prior", "2026-09-03", 1_100, "system-transport"),
        entry("outside-prior", "2026-08-31", 99_999, "system-food"),
        entry("salary-current", "2026-10-20", 90_000, "system-income", TransactionType.INCOME),
    )

    @Test
    fun explicitFollowUpsRetainCategoryAndExactCalendarPeriod() {
        val first = ask("本月餐饮支出多少？")
        assertEquals("支出 ¥20.00", first.answer.facts[1].text)
        val last = ask("上月呢？", first.resolvedQuestion)
        assertEquals("支出 ¥5.00", last.answer.facts[1].text)
        assertEquals(periods.previousStartEpochMillis, last.answer.startEpochMillis)
        val otherCategory = ask("那「交通」呢？", last.resolvedQuestion)
        assertEquals("支出 ¥11.00", otherCategory.answer.facts[1].text)
        val ranked = ask("哪笔最大？", otherCategory.resolvedQuestion)
        assertEquals(listOf("transit-prior"), ranked.answer.sources.map { it.transaction.id })

        val compared = ask("跟上月比呢？", first.resolvedQuestion)
        assertEquals("本月与上月比较 · 餐饮", compared.answer.title)
        assertEquals("上月支出 ¥5.00", compared.answer.facts[1].text)
        assertEquals("支出差额 ¥15.00", compared.answer.facts[2].text)
        assertEquals(setOf("food-current", "food-prior"), compared.answer.sources.map { it.transaction.id }.toSet())
    }

    @Test
    fun directQuestionsDoNotInheritScopeAndCanCompareNamedCategories() {
        val prior = ask("本月餐饮支出多少？")
        val direct = ask("本月收入和上月收入比较", prior.resolvedQuestion)
        assertEquals(listOf("salary-current"), direct.answer.sources.map { it.transaction.id })
        assertEquals("收入差额 ¥900.00", direct.answer.facts[2].text)
        val categoryComparison = ask("和交通比呢？", prior.resolvedQuestion)
        assertEquals("分类支出对比", categoryComparison.answer.title)
        assertEquals("「餐饮」支出 ¥20.00", categoryComparison.answer.facts.first().text)
        assertEquals("「交通」支出 ¥35.00", categoryComparison.answer.facts[1].text)
        assertEquals("餐饮 减 交通 −¥15.00", categoryComparison.answer.facts[2].text)
        assertEquals(2, categoryComparison.answer.facts[2].sourceReferences.size)
        val lastMonthPair = ask("上月呢", categoryComparison.resolvedQuestion)
        assertEquals("餐饮 减 交通 −¥6.00", lastMonthPair.answer.facts[2].text)
    }

    @Test
    fun incompleteOrUnrelatedQuestionsDoNotSilentlyReuseOldContext() {
        val old = ask("本月餐饮支出多少？").resolvedQuestion
        for (question in listOf("继续", "比较两首诗", "哪个国家人口最多", "去年收入是多少", "本周支出多少")) {
            assertFailsWith<IllegalArgumentException>(question) { ask(question, old) }
        }
        assertFailsWith<IllegalArgumentException> { ask("那交通呢？") }
    }

    @Test
    fun presetRangePersistsInFollowUpsAndCanBeOverriddenByAnExplicitMonth() {
        val chat = FinanceChat()
        val start = instant("2026-09-02").toEpochMilliseconds()
        val end = instant("2026-10-13").toEpochMilliseconds()
        fun askInRange(question: String, previous: String? = null) = chat.answerInRange(
            question, previous, rows, DefaultCategories.all, periods, start, end,
        )
        val first = askInRange("餐饮支出多少")
        assertEquals("支出 ¥25.00", first.answer.facts[1].text)
        val transport = askInRange("那交通呢", first.resolvedQuestion)
        assertEquals("支出 ¥46.00", transport.answer.facts[1].text)
        assertEquals(start, transport.answer.startEpochMillis)
        val currentMonth = askInRange("本月呢", transport.resolvedQuestion)
        assertEquals("支出 ¥35.00", currentMonth.answer.facts[1].text)
        assertEquals(periods.currentStartEpochMillis, currentMonth.answer.startEpochMillis)
    }

    @Test
    fun builtInProviderFollowUpsKeepThePreviousMonthAndFilter() {
        val original = ask("上月餐饮支出多少")
        val ranked = ask("这段时间金额最高的是哪几笔？", original.resolvedQuestion)
        assertEquals(periods.previousStartEpochMillis, ranked.answer.startEpochMillis)
        assertEquals(listOf("food-prior"), ranked.answer.sources.map { it.transaction.id })
        val compared = ask("跟前一等长期间比，支出有什么变化？", original.resolvedQuestion)
        assertEquals(periods.previousStartEpochMillis, compared.answer.startEpochMillis)
        assertEquals("前一等长期间支出 ¥999.99", compared.answer.facts[1].text)
        assertFailsWith<IllegalArgumentException> { ask("这段时间哪个国家人口最多？", original.resolvedQuestion) }
    }

    @Test
    fun optionalProviderReceivesOnlyFactsAlreadyComputedLocally() {
        val privateQuestion = "在「私密餐饮门店」花了多少？"
        val local = ask(privateQuestion)
        assertEquals(1, local.answer.matchedCount)
        assertEquals(listOf("food-current"), local.answer.sources.map { it.transaction.id })
        for (protocol in AiApiProtocol.entries) {
            val request = FinanceAi(protocol).auditRequest("test-model", local.answer)
            assertContains(request.body, "2000")
            for (value in listOf(privateQuestion, "私密餐饮门店", "receipt@example.com", "food-current", "private-account")) {
                assertFalse(request.body.contains(value), value)
            }
        }
    }

    private fun ask(question: String, previous: String? = null): FinanceChatReply =
        FinanceChat().answer(question, previous, rows, DefaultCategories.all, periods)

    private fun entry(
        id: String,
        date: String,
        amount: Long,
        category: String,
        type: TransactionType = TransactionType.EXPENSE,
    ): Transaction = Transaction(
        id = id, amount = amount, type = type, categoryId = category, accountId = "private-account",
        dateTime = instant(date),
        note = if (id == "food-current") "私密餐饮门店 receipt@example.com" else null,
    )

    private companion object {
        fun instant(date: String): Instant = Instant.parse(date + "T12:00:00Z")
    }
}

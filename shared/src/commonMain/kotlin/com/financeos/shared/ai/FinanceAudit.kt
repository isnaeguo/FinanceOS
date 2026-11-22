package com.financeos.shared.ai

import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.workspace.WorkspaceInboxKind
import com.financeos.shared.workspace.ledgerSignals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Fact text and source identifiers come from the local ledger. They are never authored by the model. */
class FinanceAuditFact internal constructor(
    val id: String,
    val text: String,
    val sourceReferences: List<String>,
    internal val metric: String,
    internal val amountMinor: Long? = null,
    internal val count: Int? = null,
)

data class FinanceAuditSource(val reference: String, val transaction: Transaction)

class FinanceAuditAnswer internal constructor(
    val title: String,
    val facts: List<FinanceAuditFact>,
    val sources: List<FinanceAuditSource>,
    val matchedCount: Int,
    val omittedSourceCount: Int,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

class FinanceAudit {
    /**
     * 回答选定时间段内的本地账本问题。调用方提供当前账本的流水，内部转账需按账本口径提前排除。
     * 日期区间为 [start, end)，对比区间紧邻所选区间且长度相同。
     */
    @Throws(IllegalArgumentException::class)
    fun answer(
        question: String,
        transactions: List<Transaction>,
        categories: List<Category>,
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): FinanceAuditAnswer {
        require(question.trim().length in 1..400) { "请输入 1–400 个字符的账本问题。" }
        return answerQuery(AuditQuery.parse(question, categories), transactions, categories, startEpochMillis, endEpochMillis)
    }

    internal fun answerQuery(
        query: AuditQuery,
        transactions: List<Transaction>,
        categories: List<Category>,
        startEpochMillis: Long,
        endEpochMillis: Long,
        comparisonStartEpochMillis: Long? = null,
        comparisonEndEpochMillis: Long? = null,
    ): FinanceAuditAnswer {
        require(startEpochMillis < endEpochMillis) { "请先选择有效的查询日期。" }
        val start = Instant.fromEpochMilliseconds(startEpochMillis)
        val end = Instant.fromEpochMilliseconds(endEpochMillis)
        val previousStart = if (query.kind == AuditKind.COMPARISON) {
            comparisonStartEpochMillis?.let { Instant.fromEpochMilliseconds(it) } ?: (start - (end - start))
        } else {
            start
        }
        val previousEnd = comparisonEndEpochMillis?.let { Instant.fromEpochMilliseconds(it) } ?: start
        val evidenceStart = when (query.kind) {
            AuditKind.DUPLICATE -> start - 10.minutes
            AuditKind.PRICE_CHANGE -> start - 80.days
            else -> previousStart
        }
        val selected = transactions.asSequence()
            .filter { it.deletedAt == null && it.dateTime >= evidenceStart && it.dateTime < end && query.matches(it) }
            .distinctBy(Transaction::id)
            .toList()
        val current = selected.filter { it.dateTime >= start }
        val categoryNames = categories.filter { it.deletedAt == null }.associate { it.id to it.name }
        val result = when (query.kind) {
            AuditKind.DUPLICATE -> signalFacts(selected, start, WorkspaceInboxKind.DUPLICATE)
            AuditKind.PRICE_CHANGE -> signalFacts(selected, start, WorkspaceInboxKind.PRICE_INCREASE)
            AuditKind.COMPARISON -> {
                comparisonFacts(
                    current,
                    selected.filter { it.dateTime >= previousStart && it.dateTime < previousEnd },
                    categoryNames,
                    query.direction ?: TransactionType.EXPENSE,
                    usesCalendarPeriod = comparisonStartEpochMillis != null,
                )
            }
            AuditKind.CATEGORY_COMPARISON -> categoryComparisonFacts(
                current, categoryNames, requireNotNull(query.categoryId), requireNotNull(query.comparisonCategoryId),
                query.direction ?: TransactionType.EXPENSE,
            )
            AuditKind.LARGEST_CATEGORY -> largestCategoryFacts(current, categoryNames)
            AuditKind.LARGEST -> largestFacts(current)
            AuditKind.TOTALS -> totalFacts(current)
        }
        val sourcesByFact = result.facts.map(DraftFact::transactions)
        val allSources = (sourcesByFact.asSequence().flatMap { it.asSequence() } + result.additionalSources.asSequence())
            .distinctBy(Transaction::id).toList()
        // Give every displayed fact an evidence row before filling the remaining slots.
        val representatives = sourcesByFact.mapNotNull { it.firstOrNull() }
        val evidence = (representatives.asSequence() + allSources.asSequence()).distinctBy(Transaction::id)
            .take(MAX_AUDIT_SOURCES).mapIndexed { index, transaction ->
                FinanceAuditSource("S${index + 1}", transaction)
            }.toList()
        val sourceIds = evidence.associate { it.transaction.id to it.reference }
        val facts = result.facts.mapIndexed { index, fact ->
            FinanceAuditFact(
                id = "F${index + 1}",
                text = fact.text,
                sourceReferences = fact.transactions.mapNotNull { sourceIds[it.id] }.distinct(),
                metric = fact.metric,
                amountMinor = fact.amount,
                count = fact.count,
            )
        }
        val title = listOfNotNull(result.title, query.filterLabel).joinToString(" · ")
        return FinanceAuditAnswer(
            title = title,
            facts = facts,
            sources = evidence,
            matchedCount = result.matchedCount,
            omittedSourceCount = allSources.size - evidence.size,
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
        )
    }

    private fun totalFacts(rows: List<Transaction>): AuditResult {
        val income = rows.filter { it.type == TransactionType.INCOME }.byAmount()
        val spending = rows.filter { it.type == TransactionType.EXPENSE }.byAmount()
        val incomeTotal = income.total()
        val spendingTotal = spending.total()
        val facts = listOf(
            DraftFact("income", "收入 ${money(incomeTotal)}", income, incomeTotal),
            DraftFact("expense", "支出 ${money(spendingTotal)}", spending, spendingTotal),
            DraftFact("net", "净收入 ${money(incomeTotal - spendingTotal)}", spending + income, incomeTotal - spendingTotal),
        )
        return AuditResult("所选期间收支", facts, rows.size)
    }

    private fun largestFacts(rows: List<Transaction>): AuditResult {
        val ranked = rows.byAmount()
        val top = ranked.take(5)
        val facts = top.mapIndexed { index, row ->
            DraftFact("largest_entry", "第 ${index + 1} 笔 ${money(row.amount)}", listOf(row), row.amount)
        }
        return AuditResult(
            "金额最高的流水",
            facts.ifEmpty { listOf(DraftFact("matches", "所选期间没有匹配流水。", count = 0)) },
            rows.size,
        )
    }

    private fun largestCategoryFacts(rows: List<Transaction>, categoryNames: Map<String, String>): AuditResult {
        val totals = rows.groupBy(Transaction::categoryId)
            .map { (id, entries) -> Triple(id, entries.total(), entries.byAmount()) }
            .sortedWith(compareByDescending<Triple<String, Long, List<Transaction>>> { it.second }.thenBy { it.first })
        val facts = totals.take(5).map { (id, total, entries) ->
            DraftFact("largest_category", "「${categoryNames[id] ?: "未命名分类"}」${money(total)}", entries, total)
        }
        return AuditResult(
            "金额最高的分类",
            facts.ifEmpty { listOf(DraftFact("matches", "所选期间没有匹配流水。", count = 0)) },
            rows.size,
        )
    }

    private fun comparisonFacts(
        current: List<Transaction>,
        previous: List<Transaction>,
        categoryNames: Map<String, String>,
        direction: TransactionType,
        usesCalendarPeriod: Boolean,
    ): AuditResult {
        val recent = current.filter { it.type == direction }.byAmount()
        val earlier = previous.filter { it.type == direction }.byAmount()
        val (metric, label) = if (direction == TransactionType.INCOME) "income" to "收入" else "expense" to "支出"
        val recentTotal = recent.total()
        val earlierTotal = earlier.total()
        val difference = recentTotal - earlierTotal
        val recentGroups = recent.groupBy(Transaction::categoryId)
        val earlierGroups = earlier.groupBy(Transaction::categoryId)
        val largestCategoryChange = (recentGroups.keys + earlierGroups.keys).sorted().map { id ->
            id to (recentGroups[id].orEmpty().total() - earlierGroups[id].orEmpty().total())
        }.maxByOrNull { (_, change) -> if (change < 0) -change else change }
        val facts = mutableListOf(
            DraftFact(metric, "所选期间$label ${money(recentTotal)}", recent, recentTotal),
            DraftFact("prior_$metric", "${if (usesCalendarPeriod) "上月" else "前一等长期间"}$label ${money(earlierTotal)}", earlier, earlierTotal),
            DraftFact("${metric}_change", "${label}差额 ${money(difference)}", recent + earlier, difference),
        )
        if (largestCategoryChange != null && largestCategoryChange.second != 0L) {
            val (id, change) = largestCategoryChange
            val category = categoryNames[id] ?: "未命名分类"
            facts += DraftFact(
                "largest_category_change",
                "变化最大的分类是「$category」，差额 ${money(change)}",
                recentGroups[id].orEmpty() + earlierGroups[id].orEmpty(),
                change,
            )
        }
        return AuditResult(
            if (usesCalendarPeriod) "本月与上月比较" else "与前一等长期间比较", facts, recent.size + earlier.size,
        )
    }

    private fun categoryComparisonFacts(
        rows: List<Transaction>,
        categoryNames: Map<String, String>,
        firstId: String,
        secondId: String,
        direction: TransactionType,
    ): AuditResult {
        val first = rows.filter { it.categoryId == firstId && it.type == direction }.byAmount()
        val second = rows.filter { it.categoryId == secondId && it.type == direction }.byAmount()
        val firstName = categoryNames[firstId] ?: "未命名分类"
        val secondName = categoryNames[secondId] ?: "未命名分类"
        val firstTotal = first.total()
        val secondTotal = second.total()
        val difference = firstTotal - secondTotal
        val metric = if (direction == TransactionType.INCOME) "income" else "expense"
        val label = if (direction == TransactionType.INCOME) "收入" else "支出"
        return AuditResult(
            "分类${label}对比",
            listOf(
                DraftFact("first_category_$metric", "「$firstName」$label ${money(firstTotal)}", first, firstTotal),
                DraftFact("second_category_$metric", "「$secondName」$label ${money(secondTotal)}", second, secondTotal),
                DraftFact("category_${metric}_difference", "$firstName 减 $secondName ${money(difference)}", first + second, difference),
            ),
            first.size + second.size,
        )
    }

    private fun signalFacts(rows: List<Transaction>, start: Instant, kind: WorkspaceInboxKind): AuditResult {
        val byId = rows.associateBy(Transaction::id)
        val signals = ledgerSignals(null, rows).filter { entry ->
            entry.kind == kind && entry.transactionIds.any { id -> byId.getValue(id).dateTime >= start }
        }.toList()
        val isDuplicate = kind == WorkspaceInboxKind.DUPLICATE
        val summary = if (isDuplicate) "疑似重复流水" else "周期支出疑似涨价"
        val facts = mutableListOf(DraftFact(
            "signal_count", "所选期间有 ${signals.size} 项$summary。",
            signals.firstOrNull()?.transactionIds.orEmpty().map(byId::getValue), count = signals.size,
        ))
        val visible = signals.take(MAX_AUDIT_SOURCES / (if (isDuplicate) 2 else 3))
        for (entry in visible) {
            val evidence = entry.transactionIds.map(byId::getValue)
            if (isDuplicate) {
                facts += DraftFact("duplicate_candidate", "同一账户短时间出现同金额 ${money(entry.amountMinor)}", evidence, entry.amountMinor)
            } else {
                val before = evidence[1].amount
                facts += DraftFact(
                    "price_change_candidate",
                    "同一付款备注的周期支出从 ${money(before)} 变为 ${money(entry.amountMinor)}",
                    evidence,
                    entry.amountMinor - before,
                )
            }
        }
        val allSignalIds = signals.asSequence().flatMap { it.transactionIds.asSequence() }.distinct().toList()
        val additional = signals.drop(visible.size).flatMap { it.transactionIds }.distinct().map(byId::getValue)
        return AuditResult(summary, facts, allSignalIds.size, additional)
    }
}

internal enum class AuditKind { TOTALS, COMPARISON, CATEGORY_COMPARISON, LARGEST, LARGEST_CATEGORY, DUPLICATE, PRICE_CHANGE }

internal data class AuditQuery(
    val kind: AuditKind,
    val direction: TransactionType?,
    val categoryId: String?,
    val merchant: String?,
    val filterLabel: String?,
    val comparisonCategoryId: String? = null,
) {
    fun matches(row: Transaction): Boolean =
        (direction == null || row.type == direction) &&
            (categoryId == null || row.categoryId == categoryId || row.categoryId == comparisonCategoryId) &&
            (merchant == null || row.note.orEmpty().contains(merchant, ignoreCase = true))

    companion object {
        private val quotedName = Regex("[「“\"]([^」”\"]{1,80})[」”\"]")
        private val merchantName = Regex("""(?:商户[:：]\s*|在(?!过去|最近|所选|这个月|本月|上月|这段|今年|去年|月初|本周|上周|\d))([^？?，,。]{1,64}?)(?:花了|消费了|消费|花费|支出|的流水|的交易|$)""")
        private val incomeWords = listOf("收入", "入账", "赚了", "进账", "income", "earn", "received")
        private val expenseWords = listOf("支出", "消费", "花了", "花费", "开销", "扣款", "多少钱", "spend", "expense", "cost")

        fun parse(question: String, categories: List<Category>): AuditQuery {
            val text = question.trim().lowercase()
            fun includes(vararg terms: String): Boolean = terms.any(text::contains)
            val asksIncome = incomeWords.any(text::contains)
            val asksExpense = expenseWords.any(text::contains)
            val comparison = includes("多花", "少花", "变化", "对比", "比较", "同期", "怎么变", "compare", "previous", "change")
            val ranking = includes("最高", "最大", "最多", "排名", "哪几笔", "largest", "biggest", "top expense")
            val kind = when {
                includes("重复", "多扣", "duplicate", "double charg") -> AuditKind.DUPLICATE
                includes("涨价", "涨了", "变贵", "price increase", "renewal increase") -> AuditKind.PRICE_CHANGE
                comparison -> AuditKind.COMPARISON
                ranking && includes("分类", "category", "categories") -> AuditKind.LARGEST_CATEGORY
                ranking -> AuditKind.LARGEST
                asksExpense || asksIncome || includes("收支", "结余", "净额", "汇总", "账单", "summary", "net", "total") -> AuditKind.TOTALS
                else -> throw IllegalArgumentException("可查询选定期间的收支、金额排名、收支变化、重复扣款或周期涨价。")
            }
            val category = categories.asSequence()
                .filter { it.deletedAt == null && it.name.length > 1 && text.contains(it.name.lowercase()) }
                .sortedWith(compareByDescending<Category> { it.name.length }.thenBy { it.id })
                .firstOrNull()
            val term = (quotedName.find(question) ?: merchantName.find(question))?.groupValues?.get(1)?.trim()
            val merchant = term?.takeIf { it.isNotBlank() && (category == null || !it.equals(category.name, ignoreCase = true)) }
            val hasNetScope = includes("收支", "结余", "净额", "净收入", "净支出", "net")
            val direction = when {
                kind == AuditKind.PRICE_CHANGE -> TransactionType.EXPENSE
                kind == AuditKind.COMPARISON -> if (asksIncome && !asksExpense) TransactionType.INCOME else TransactionType.EXPENSE
                kind == AuditKind.LARGEST_CATEGORY && !asksIncome && !asksExpense -> TransactionType.EXPENSE
                hasNetScope || asksIncome == asksExpense -> null
                asksIncome -> TransactionType.INCOME
                else -> TransactionType.EXPENSE
            }
            return AuditQuery(kind, direction, category?.id, merchant, listOfNotNull(category?.name, merchant).joinToString(" / ").ifBlank { null })
        }
    }
}

private data class AuditResult(
    val title: String,
    val facts: List<DraftFact>,
    val matchedCount: Int,
    val additionalSources: List<Transaction> = emptyList(),
)

private data class DraftFact(
    val metric: String,
    val text: String,
    val transactions: List<Transaction> = emptyList(),
    val amount: Long? = null,
    val count: Int? = null,
)

private fun List<Transaction>.byAmount(): List<Transaction> = sortedWith(
    compareByDescending<Transaction> { it.amount }.thenByDescending { it.dateTime }.thenBy { it.id },
)

private fun List<Transaction>.total(): Long = fold(0L) { total, row ->
    require(total <= Long.MAX_VALUE - row.amount) { "账本汇总超出可分析的金额范围。" }
    total + row.amount
}

private fun money(amount: Long): String {
    val magnitude = if (amount < 0) -amount else amount
    return "${if (amount < 0) "−" else ""}¥${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}"
}

private const val MAX_AUDIT_SOURCES = 30

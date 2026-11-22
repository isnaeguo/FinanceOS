package com.financeos.shared.ai

import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType

/** Bounds come from the caller's local calendar, so February and DST changes compare correctly. */
data class FinanceChatPeriods(
    val currentStartEpochMillis: Long,
    val currentEndEpochMillis: Long,
    val previousStartEpochMillis: Long,
    val previousEndEpochMillis: Long,
)

data class FinanceChatReply(val resolvedQuestion: String, val answer: FinanceAuditAnswer)

/**
 * A narrowly scoped local ledger conversation. Only the resolved previous question is needed for
 * explicit follow-ups. Providers may reorder validated facts using the existing audit API.
 */
class FinanceChat {
    @Throws(IllegalArgumentException::class)
    fun answer(
        question: String,
        previousQuestion: String?,
        transactions: List<Transaction>,
        categories: List<Category>,
        periods: FinanceChatPeriods,
    ): FinanceChatReply = answerInRange(
        question, previousQuestion, transactions, categories, periods,
        periods.currentStartEpochMillis, periods.currentEndEpochMillis,
    )

    @Throws(IllegalArgumentException::class)
    fun answerInRange(
        question: String,
        previousQuestion: String?,
        transactions: List<Transaction>,
        categories: List<Category>,
        periods: FinanceChatPeriods,
        rangeStartEpochMillis: Long,
        rangeEndEpochMillis: Long,
    ): FinanceChatReply {
        require(question.trim().length in 1..400) { "请输入 1–400 个字符的账本问题。" }
        require(periods.previousStartEpochMillis < periods.previousEndEpochMillis &&
            periods.previousEndEpochMillis <= periods.currentStartEpochMillis &&
            periods.currentStartEpochMillis < periods.currentEndEpochMillis &&
            rangeStartEpochMillis < rangeEndEpochMillis) { "查询日期范围无效。" }
        val parser = ChatQuestions(categories.filter { it.deletedAt == null })
        val previous = previousQuestion?.takeIf { it.length in 1..400 }?.let {
            runCatching { parser.resolve(it, null) }.getOrNull()
        }
        val intent = parser.resolve(question, previous)
        val (start, end) = when (intent.period) {
            ChatPeriod.DEFAULT -> rangeStartEpochMillis to rangeEndEpochMillis
            ChatPeriod.CURRENT -> periods.currentStartEpochMillis to periods.currentEndEpochMillis
            ChatPeriod.PREVIOUS -> periods.previousStartEpochMillis to periods.previousEndEpochMillis
        }
        val compareMonths = intent.query.kind == AuditKind.COMPARISON &&
            intent.period == ChatPeriod.CURRENT && !intent.equalLengthComparison
        val answer = FinanceAudit().answerQuery(
            query = intent.query,
            transactions = transactions,
            categories = categories,
            startEpochMillis = start,
            endEpochMillis = end,
            comparisonStartEpochMillis = periods.previousStartEpochMillis.takeIf { compareMonths },
            comparisonEndEpochMillis = periods.previousEndEpochMillis.takeIf { compareMonths },
        )
        return FinanceChatReply(parser.describe(intent), answer)
    }
}

private enum class ChatPeriod(val label: String) { DEFAULT("所选期间"), CURRENT("本月"), PREVIOUS("上月") }
private data class ChatIntent(
    val query: AuditQuery,
    val period: ChatPeriod,
    val equalLengthComparison: Boolean = false,
)

private class ChatQuestions(private val categories: List<Category>) {
    fun resolve(question: String, previous: ChatIntent?): ChatIntent {
        val text = question.trim().lowercase()
        val subjectless = quotes.replace(text, "")
        require(!unsupportedDate.containsMatchIn(subjectless)) { "账本聊天目前可以查询本月和上月。" }
        val asksCurrent = currentMonth.containsMatchIn(subjectless)
        val asksPrevious = previousMonth.containsMatchIn(subjectless)
        val asksSameRange = relativeRange.containsMatchIn(subjectless)
        val asksEqualLength = subjectless.contains("前一等长期间")
        val explicitPeriod = when {
            asksCurrent -> ChatPeriod.CURRENT
            asksPrevious -> ChatPeriod.PREVIOUS
            defaultRange.containsMatchIn(subjectless) -> ChatPeriod.DEFAULT
            asksSameRange -> previous?.period ?: ChatPeriod.DEFAULT
            else -> null
        }
        val words = compact(text)
        val stripped = compact(withoutPeriod(subjectless))
        val short = stripped.removePrefix("那么").removePrefix("那").removeSuffix("怎么样").removeSuffix("呢")
        val followUp = words.startsWith("那") || words.endsWith("呢") || words.endsWith("怎么样")
        val asksRanking = rankingShorthand.matches(short)
        val inheritContext = followUp || asksRanking || asksSameRange || asksEqualLength
        val isCompare = comparison.containsMatchIn(subjectless) || comparisonWithPrevious.containsMatchIn(subjectless) ||
            listOf("和", "跟", "与", "比").any(short::startsWith) && followUp
        val namedCategories = namedCategories(text)
        val ledgerSignal = namedCategories.isNotEmpty() || ledgerTerms.containsMatchIn(subjectless)

        if (short.isEmpty() && explicitPeriod != null && !isCompare) {
            val prior = previous ?: unsupported()
            return ChatIntent(prior.query.copy(
                kind = if (prior.query.kind == AuditKind.COMPARISON) AuditKind.TOTALS else prior.query.kind,
            ), explicitPeriod)
        }

        val subjectShorthand = compact(withoutPeriod(text))
            .removePrefix("那么").removePrefix("那").removeSuffix("怎么样").removeSuffix("呢")
            .trim('「', '」', '“', '”', '"')
        val singleCategory = categories.filter { compact(it.name) == subjectShorthand }
        if (singleCategory.isNotEmpty() && !isCompare) {
            val prior = previous ?: unsupported()
            require(singleCategory.size == 1) { "有多个同名分类，请先在账本中区分名称。" }
            val category = singleCategory.single()
            val kind = if (prior.query.kind in setOf(AuditKind.LARGEST, AuditKind.LARGEST_CATEGORY)) prior.query.kind else AuditKind.TOTALS
            return ChatIntent(
                AuditQuery(kind, prior.query.direction, category.id, null, category.name),
                explicitPeriod ?: prior.period,
            )
        }

        if (isCompare) {
            val comparingPrevious = previous != null && monthComparisonFollow.matches(compact(subjectless))
            val inherited = previous?.takeIf { inheritContext || comparingPrevious }
            val direction = explicitDirection(subjectless) ?: inherited?.query?.direction ?: TransactionType.EXPENSE
            val previousCategory = previous?.query?.categoryId?.let { id -> categories.find { it.id == id } }
            val categoryPair = if (namedCategories.size >= 2) namedCategories else {
                val only = namedCategories.singleOrNull()
                if (only != null && previousCategory != null && only.id != previousCategory.id && followUp &&
                    listOf("和", "跟", "与", "比").any(short::startsWith)
                ) {
                    listOf(previousCategory, only)
                } else {
                    emptyList()
                }
            }
            if (categoryPair.isNotEmpty()) {
                require(categoryPair.size == 2 && !(asksCurrent && asksPrevious)) { "请一次比较两个分类或两个月。" }
                val (first, second) = categoryPair
                return ChatIntent(
                    AuditQuery(AuditKind.CATEGORY_COMPARISON, direction, first.id, null, null, second.id),
                    explicitPeriod ?: inherited?.period ?: ChatPeriod.DEFAULT,
                )
            }
            if (!ledgerSignal && !comparingPrevious) unsupported()
            require(asksEqualLength || !(asksPrevious && !asksCurrent && inherited?.period == ChatPeriod.PREVIOUS)) {
                "当前已经在看上月。请指定要比较的分类，或问“本月和上月比”。"
            }
            require(asksEqualLength || !asksPrevious || asksCurrent || previous != null || comparisonWithPrevious.containsMatchIn(subjectless)) {
                "请指明与上月比较，或查询上月收支。"
            }
            val parsed = runCatching { AuditQuery.parse(question, categories) }.getOrNull()
            val category = namedCategories.singleOrNull() ?: previousCategory.takeIf { inherited != null }
            val merchant = if (category == null) parsed?.merchant ?: inherited?.query?.merchant else null
            return ChatIntent(
                AuditQuery(AuditKind.COMPARISON, direction, category?.id, merchant, category?.name ?: merchant),
                if (asksEqualLength) explicitPeriod ?: inherited?.period ?: ChatPeriod.DEFAULT
                else if (asksCurrent || asksPrevious) ChatPeriod.CURRENT else ChatPeriod.DEFAULT,
                equalLengthComparison = asksEqualLength,
            )
        }

        require(!(asksCurrent && asksPrevious) && hasReportIntent(subjectless) &&
            (ledgerSignal || asksRanking || followUp && short == "排名" && previous != null)) {
            "可以问本月或上月的收支、排名、分类对比、疑似重复扣款或涨价。"
        }
        var parsed = AuditQuery.parse(question, categories)
        if (namedCategories.size == 1) {
            val category = namedCategories.single()
            parsed = parsed.copy(categoryId = category.id, merchant = null, filterLabel = category.name)
        } else if (namedCategories.size > 1) {
            throw IllegalArgumentException("请指定一个分类，或用“对比”比较两个分类。")
        } else if (parsed.categoryId != null) {
            // The earlier audit grammar may match a category as a substring of a quoted merchant.
            parsed = parsed.copy(categoryId = null, filterLabel = parsed.merchant)
        }
        if (inheritContext && previous != null && parsed.categoryId == null && parsed.merchant == null) {
            val subject = previous.query
            parsed = parsed.copy(
                categoryId = subject.categoryId.takeIf { subject.comparisonCategoryId == null },
                merchant = subject.merchant.takeIf { subject.comparisonCategoryId == null },
                filterLabel = subject.filterLabel.takeIf { subject.comparisonCategoryId == null },
                direction = when {
                    parsed.kind == AuditKind.PRICE_CHANGE -> TransactionType.EXPENSE
                    hasNetMetric.containsMatchIn(subjectless) -> null
                    else -> explicitDirection(subjectless) ?: subject.direction ?: parsed.direction
                },
            )
        }
        return ChatIntent(parsed, explicitPeriod ?: previous?.period?.takeIf { inheritContext } ?: ChatPeriod.DEFAULT)
    }

    fun describe(intent: ChatIntent): String {
        val query = intent.query
        val subject = query.categoryId?.let { id -> categories.find { it.id == id }?.name } ?: query.merchant
        val scope = subject?.let { "「$it」" }.orEmpty()
        val direction = when (query.direction) {
            TransactionType.EXPENSE -> "支出"
            TransactionType.INCOME -> "收入"
            null -> "收支"
        }
        val label = intent.period.label
        return when (query.kind) {
            AuditKind.COMPARISON -> if (intent.period == ChatPeriod.CURRENT && !intent.equalLengthComparison) {
                "本月与上月$scope${direction}对比"
            } else {
                "$label$scope${direction}与前一等长期间对比"
            }
            AuditKind.CATEGORY_COMPARISON -> {
                val other = categories.first { it.id == query.comparisonCategoryId }.name
                "$label${scope}与「$other」${direction}对比"
            }
            AuditKind.TOTALS -> "$label$scope${direction}汇总"
            AuditKind.LARGEST -> "$label$scope${direction}金额最高的流水"
            AuditKind.LARGEST_CATEGORY -> "$label$scope${direction}最高的分类"
            AuditKind.DUPLICATE -> "$label$scope${direction}疑似重复扣款"
            AuditKind.PRICE_CHANGE -> "$label$scope${direction}疑似涨价"
        }
    }

    private fun namedCategories(text: String): List<Category> {
        val normalized = compact(text)
        val cited = quotes.findAll(normalized).toList()
        val searchable = quotes.replace(normalized) { " ".repeat(it.value.length) }
        val matches = categories.mapNotNull { category ->
            val name = compact(category.name)
            val quotedPosition = cited.firstOrNull { compact(it.groupValues[1]) == name }?.range?.first
            val position = quotedPosition ?: if (name.length >= 2) searchable.indexOf(name) else -1
            if (position >= 0) Triple(position, name, category) else null
        }.sortedWith(compareBy<Triple<Int, String, Category>> { it.first }.thenByDescending { it.second.length })
        val distinctNames = mutableSetOf<String>()
        val selected = mutableListOf<Category>()
        var end = -1
        for ((position, name, category) in matches) {
            if (position < end || !distinctNames.add(name)) continue
            require(matches.count { it.second == name } == 1) { "有多个同名分类，请先在账本中区分名称。" }
            selected += category
            end = position + name.length
        }
        return selected
    }

    private fun explicitDirection(text: String): TransactionType? {
        val expense = expenseTerms.containsMatchIn(text)
        val income = incomeTerms.containsMatchIn(text)
        return when {
            hasNetMetric.containsMatchIn(text) || expense == income -> null
            income -> TransactionType.INCOME
            else -> TransactionType.EXPENSE
        }
    }

    private fun hasReportIntent(text: String): Boolean =
        reportRequest.containsMatchIn(text) || bareMetric.matches(
            compact(withoutPeriod(text))
                .removePrefix("那么").removePrefix("那").removeSuffix("呢"),
        )

    private fun unsupported(): Nothing =
        throw IllegalArgumentException("请先问一个具体的账本问题，例如“本月餐饮支出多少”。")

    private fun withoutPeriod(text: String): String =
        relativeRange.replace(defaultRange.replace(currentMonth.replace(previousMonth.replace(text, ""), ""), ""), "")

    private companion object {
        val spacing = Regex("[\\s？?，,。！!]+")
        fun compact(text: String): String = spacing.replace(text.lowercase(), "")
        val quotes = Regex("[「“\"]([^」”\"]{1,100})[」”\"]")
        val currentMonth = Regex("本月|这个月|当月|this\\s+month")
        val previousMonth = Regex("上个月|上月|last\\s+month|previous\\s+month")
        val defaultRange = Regex("所选期间|所选范围")
        val relativeRange = Regex("这段时间")
        val unsupportedDate = Regex("上上月|上上个月|下个?月|本周|上周|今年|去年|昨天|今天|明天|[0-9]+\\s*[年月日天]|(?:last|past)\\s+\\d+\\s+days")
        val comparison = Regex("对比|比较|相比|相较|变化|多花|少花|怎么变|compare|versus|\\bvs\\b")
        val comparisonWithPrevious = Regex("(?:和|跟|与|比)上个?月|compare.+(?:last|previous)\\s+month")
        val monthComparisonFollow = Regex("(?:那么|那)?(?:(?:跟|和|与|比)?上个?月(?:比|相比|对比|比较)?|(?:比较|对比)上个?月)(?:呢|怎么样)?")
        val expenseTerms = Regex("支出|消费|花了|花费|开销|扣款|spend|expense|cost")
        val incomeTerms = Regex("收入|入账|赚了|进账|income|earn|received")
        val hasNetMetric = Regex("收支|结余|净额|净收入|净支出|\\bnet\\b")
        val bareMetric = Regex("收入|支出|消费|收支|结余|净额|净收入|净支出|账单|汇总|income|expenses?|summary|net")
        val reportRequest = Regex("多少|汇总|合计|总额|最高|最大|最多|排名|哪几笔|哪几类|重复|多扣|涨价|变贵|涨了|(?:收入|支出|收支|消费|income|expenses?)怎么样|how much|total|largest|biggest|duplicate|price increase")
        val ledgerTerms = Regex("收入|支出|花了|花费|消费|开销|结余|净额|收支|扣款|流水|账单|分类|商户|周期涨价|income|expense|spend|ledger|transaction|category|duplicate|price increase|summary|total|net")
        val rankingShorthand = Regex("哪笔最大|哪几笔最大|最大的几笔(?:是什么)?|哪几笔|最大的一笔(?:是什么)?|金额最高的是哪几笔")
    }
}

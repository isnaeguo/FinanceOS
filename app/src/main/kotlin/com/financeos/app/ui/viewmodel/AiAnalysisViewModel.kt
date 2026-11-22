package com.financeos.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.app.ai.AiConnection
import com.financeos.app.ai.AiConnectionStore
import com.financeos.app.ai.AiRequestException
import com.financeos.app.ai.AndroidAiTransport
import com.financeos.app.ai.aiEndpoint
import com.financeos.shared.ai.AiCategorySuggestion
import com.financeos.shared.ai.AiSpendingAdvice
import com.financeos.shared.ai.FinanceAi
import com.financeos.shared.data.local.repository.LocalTransactionRepository
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.repository.BudgetRepository
import com.financeos.shared.domain.repository.CategoryRepository
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CategorySuggestionUiState(
    val suggestion: AiCategorySuggestion,
    val transaction: Transaction,
    val categoryName: String,
    val dateLabel: String,
) {
    val amountText = (if (transaction.type == TransactionType.EXPENSE) "−" else "+") +
        formatMoney(transaction.amount)
}

internal data class CategoryBatchProgress(val completed: Int, val total: Int)

internal data class CategoryReview(
    val item: CategorySuggestionUiState,
    val options: List<Category>,
)

internal data class AiAnalysisUiState(
    val month: YearMonth,
    val canShowNextMonth: Boolean = false,
    val endpoint: String = "https://api.openai.com/v1",
    val model: String = "",
    val isConfigured: Boolean = false,
    val activity: String? = null,
    val canCancel: Boolean = false,
    val suggestions: List<CategorySuggestionUiState> = emptyList(),
    val classificationProgress: CategoryBatchProgress? = null,
    val classificationSummary: String? = null,
    val classificationError: String? = null,
    val categoryReview: CategoryReview? = null,
    val advice: AiSpendingAdvice? = null,
    val message: String? = null,
    val error: String? = null,
)

internal class AiAnalysisViewModel(
    private val store: AiConnectionStore,
    private val transactions: LocalTransactionRepository,
    private val categories: CategoryRepository,
    private val budgets: BudgetRepository,
    private val transport: AndroidAiTransport = AndroidAiTransport(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val _state = MutableStateFlow(AiAnalysisUiState(month = YearMonth.now(zone)))
    val state = _state.asStateFlow()
    private var connection: AiConnection? = null
    private var operation: Job? = null

    init {
        runOperation("读取配置", "无法读取 AI 配置，可清除后重新配置。") {
            connection = withContext(Dispatchers.IO) { store.load() }
            connection?.let { settings ->
                _state.update {
                    it.copy(endpoint = settings.endpoint.toString(), model = settings.model, isConfigured = true)
                }
            }
        }
    }

    fun saveSettings(endpoint: String, model: String, apiKey: String) {
        runOperation("保存配置", "无法保存 AI 配置，请稍后重试。") {
            val resolvedEndpoint = aiEndpoint(endpoint)
            val resolvedModel = model.trim()
            require(resolvedModel.isNotEmpty() && resolvedModel.length <= 160 && resolvedModel.none(Char::isISOControl)) {
                "请输入服务提供方的模型名称。"
            }
            val key = apiKey.trim().ifEmpty {
                connection?.takeIf { it.endpoint == resolvedEndpoint }?.apiKey.orEmpty()
            }
            require(key.length <= 4_096 && key.all { it.code in 33..126 }) {
                "请输入有效的 API 密钥。"
            }
            val next = AiConnection(resolvedEndpoint, resolvedModel, key)
            withContext(Dispatchers.IO) { store.save(next) }
            connection = next
            _state.update {
                it.copy(
                    endpoint = next.endpoint.toString(),
                    model = next.model,
                    isConfigured = true,
                    suggestions = emptyList(),
                    classificationSummary = null,
                    classificationError = null,
                    advice = null,
                    message = "配置已保存。",
                )
            }
        }
    }

    fun clearSettings() {
        runOperation("清除配置", "无法清除 AI 配置，请稍后重试。") {
            withContext(Dispatchers.IO) { store.clear() }
            connection = null
            _state.update {
                AiAnalysisUiState(
                    month = it.month,
                    canShowNextMonth = it.canShowNextMonth,
                    activity = it.activity,
                    message = "AI 配置已清除。",
                )
            }
        }
    }

    fun previousMonth() = changeMonth(-1)

    fun nextMonth() = changeMonth(1)

    fun suggestCategories() {
        val settings = connection ?: return
        val period = _state.value.month.toMonthPeriod(zone)
        runOperation(
            "请求分类建议",
            "无法完成分类，请检查网络与 AI 配置。",
            canCancel = true,
            onError = { message -> _state.update { it.copy(classificationError = message) } },
        ) {
            _state.update { it.copy(classificationSummary = null, classificationError = null) }
            val snapshot = transactions.getByPeriod(period.startInclusive, period.endExclusive)
            val allowedCategories = categories.getAll()
            val (requests, records) = withContext(Dispatchers.Default) {
                val planner = FinanceAi(settings.protocol)
                planner.classificationRequests(settings.model, snapshot, allowedCategories) to
                    snapshot.associateBy(Transaction::id)
            }
            val categoryNames = allowedCategories.associate { it.id to it.name }
            val pending = _state.value.suggestions
                .filter { records[it.transaction.id] == it.transaction }
                .associateByTo(linkedMapOf()) { it.transaction.id }
            val total = requests.sumOf { it.transactionCount }
            var completed = 0
            var suggestionCount = 0

            _state.update {
                it.copy(
                    suggestions = pending.values.toList(),
                    classificationProgress = CategoryBatchProgress(completed, total),
                )
            }
            try {
                for (request in requests) {
                    currentCoroutineContext().ensureActive()
                    val response = transport.complete(settings, request.body)
                    val candidates = withContext(Dispatchers.Default) {
                        request.parse(response).mapNotNull { suggestion ->
                            val transaction = records[suggestion.transactionId] ?: return@mapNotNull null
                            val name = categoryNames[suggestion.categoryId] ?: return@mapNotNull null
                            val date = java.time.Instant.ofEpochMilli(transaction.dateTime.toEpochMilliseconds())
                                .atZone(zone)
                            CategorySuggestionUiState(suggestion, transaction, name, reviewDateFormatter.format(date))
                        }
                    }
                    completed += request.transactionCount
                    suggestionCount += candidates.size
                    for (candidate in candidates) pending[candidate.transaction.id] = candidate
                    val suggestions = pending.values.toList()
                    _state.update {
                        it.copy(
                            suggestions = suggestions,
                            classificationProgress = CategoryBatchProgress(completed, total),
                        )
                    }
                }
            } finally {
                val summary = if (completed == total) {
                    "已分析 $completed 笔，收到 $suggestionCount 条建议。"
                } else {
                    "本次完成 $completed / $total 笔，已保留收到的建议。"
                }
                _state.update { it.copy(classificationProgress = null, classificationSummary = summary) }
            }
        }
    }

    fun analyzeSpending() {
        val settings = connection ?: return
        val period = _state.value.month.toMonthPeriod(zone)
        runOperation("请求消费建议", "无法完成分析，请检查网络与 AI 配置。", canCancel = true) {
            val snapshot = transactions.getByPeriod(period.startInclusive, period.endExclusive)
            val knownCategories = categories.getAll()
            val monthBudgets = budgets.getByMonth(period.month)
            val advice = withContext(Dispatchers.Default) {
                val planner = FinanceAi(settings.protocol)
                val body = planner.spendingRequest(settings.model, snapshot, knownCategories, period, monthBudgets)
                planner.parseSpending(transport.complete(settings, body))
            }
            _state.update { it.copy(advice = advice) }
        }
    }

    fun reviewSuggestion(item: CategorySuggestionUiState) {
        if (item !in _state.value.suggestions) return
        runOperation("读取分类", "无法读取可选分类，请稍后重试。") {
            val options = compatibleCategories(item)
            _state.update { it.copy(categoryReview = CategoryReview(item, options)) }
        }
    }

    fun dismissReview() {
        if (_state.value.activity == null) _state.update { it.copy(categoryReview = null, error = null) }
    }

    fun confirmCategory(selectedId: String?, newName: String?) {
        val review = _state.value.categoryReview ?: return
        val item = review.item
        runOperation("保存分类", "无法保存分类，请稍后重试。") {
            val applied = if (newName != null) {
                transactions.updateCategoryWithNewCategory(item.transaction, "user-${UUID.randomUUID()}", newName)
            } else {
                val choice = review.options.find { it.id == selectedId }
                    ?: throw IllegalArgumentException("请选择一个分类。")
                transactions.updateCategory(item.transaction, choice.id)
            }
            if (applied) {
                removeSuggestion(item)
                _state.update { it.copy(categoryReview = null, message = "分类已更新。") }
            } else if (transactions.get(item.transaction.id) != item.transaction) {
                removeSuggestion(item)
                _state.update { it.copy(categoryReview = null, error = "该流水已变化，请重新获取建议。") }
            } else {
                val options = compatibleCategories(item)
                _state.update { it.copy(categoryReview = review.copy(options = options)) }
                throw IllegalArgumentException("此分类已变化，请选择其他分类或新建分类。")
            }
        }
    }

    private suspend fun compatibleCategories(item: CategorySuggestionUiState): List<Category> =
        categories.getAll().filter { category ->
            category.deletedAt == null && category.id != item.transaction.categoryId &&
                (category.type == CategoryType.COMMON || category.type.name == item.transaction.type.name)
        }.sortedBy(Category::name)

    fun dismiss(suggestion: CategorySuggestionUiState) {
        if (_state.value.activity == null) removeSuggestion(suggestion)
    }

    fun cancel() {
        if (_state.value.canCancel) operation?.cancel()
    }

    private fun removeSuggestion(suggestion: CategorySuggestionUiState) {
        _state.update { it.copy(suggestions = it.suggestions - suggestion) }
    }

    private fun changeMonth(by: Long) {
        if (_state.value.activity != null) return
        val currentMonth = YearMonth.now(zone)
        _state.update {
            val month = minOf(it.month.plusMonths(by), currentMonth)
            it.copy(
                month = month,
                canShowNextMonth = month < currentMonth,
                suggestions = emptyList(),
                classificationSummary = null,
                classificationError = null,
                categoryReview = null,
                advice = null,
                message = null,
                error = null,
            )
        }
    }

    private fun runOperation(
        activity: String,
        fallback: String,
        canCancel: Boolean = false,
        onError: ((String) -> Unit)? = null,
        block: suspend () -> Unit,
    ) {
        if (_state.value.activity != null) return
        _state.update { it.copy(activity = activity, canCancel = canCancel, message = null, error = null) }
        operation = viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = when (error) {
                    is AiRequestException, is IllegalArgumentException -> error.message ?: fallback
                    else -> fallback
                }
                if (onError == null) {
                    _state.update { it.copy(error = message) }
                } else {
                    onError(message)
                }
            } finally {
                _state.update { it.copy(activity = null, canCancel = false) }
            }
        }
    }
}

private val reviewDateFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)

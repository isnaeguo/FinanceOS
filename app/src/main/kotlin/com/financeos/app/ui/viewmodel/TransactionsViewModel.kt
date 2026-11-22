package com.financeos.app.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.repository.CategoryRepository
import com.financeos.shared.domain.repository.TransactionRepository
import com.financeos.shared.domain.usecase.GetMonthlyTransactionsUseCase
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 流水列表单行展示状态。 */
@Immutable
data class TransactionItemUiState(
    val id: String,
    val categoryId: String,
    val categoryName: String,
    val categoryIconKey: String,
    val accountId: String?,
    val note: String?,
    val amountText: String,
    val dateTimeText: String,
    val typeLabel: String,
    val isExpense: Boolean,
    val type: TransactionType,
    /** 排序使用的原始最小货币单位金额，展示仍统一走 [amountText]。 */
    val amountMinor: Long = 0L,
) {
    val detailText = buildString {
        append(typeLabel)
        append(" · ")
        append(dateTimeText)
        if (accountId != null) {
            append(" · ")
            append(accountId)
        }
    }
}

/** 账户管理尚未进入 v0.2，筛选直接表达现有流水中的 accountId。 */
sealed interface AccountFilter {
    data object All : AccountFilter
    data object Unspecified : AccountFilter
    data class Specific(val accountId: String) : AccountFilter
}

data class TransactionFilterOption(
    val id: String,
    val label: String,
)

/** 流水列表的排序方式，默认按时间倒序；金额排序只在当前月的筛选结果内生效。 */
enum class AmountSort(val label: String) {
    TIME_NEWEST("时间最新"),
    AMOUNT_LARGEST("金额最大"),
    AMOUNT_SMALLEST("金额最小"),
}

/** 流水列表页面状态。 */
data class TransactionsUiState(
    val monthLabel: String,
    val canShowNextMonth: Boolean = false,
    val isLoading: Boolean = true,
    val items: List<TransactionItemUiState> = emptyList(),
    val searchQuery: String = "",
    val selectedType: TransactionType? = null,
    val selectedCategoryId: String? = null,
    val selectedAccount: AccountFilter = AccountFilter.All,
    val amountSort: AmountSort = AmountSort.TIME_NEWEST,
    val categoryOptions: List<TransactionFilterOption> = emptyList(),
    val accountOptions: List<TransactionFilterOption> = emptyList(),
    val hasActiveFilters: Boolean = false,
    val pendingDeleteItem: TransactionItemUiState? = null,
    val isDeleting: Boolean = false,
    val errorMessage: String? = null,
)

/** 流水页只执行一次的轻量反馈。 */
sealed interface TransactionsEvent {
    data class ShowMessage(val message: String) : TransactionsEvent
}

/** 管理按月流水、删除确认状态及 Repository 的响应式更新。 */
class TransactionsViewModel(
    private val getMonthlyTransactions: GetMonthlyTransactionsUseCase,
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    initialMonth: YearMonth? = null,
    currentMonth: YearMonth? = null,
) : ViewModel() {
    private val latestAllowedMonth = currentMonth ?: YearMonth.now(zoneId)
    private var selectedMonth = minOf(initialMonth ?: latestAllowedMonth, latestAllowedMonth)
    private val _uiState = MutableStateFlow(
        TransactionsUiState(
            monthLabel = monthFormatter.format(selectedMonth),
            canShowNextMonth = selectedMonth < latestAllowedMonth,
        ),
    )
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private val _events = Channel<TransactionsEvent>(capacity = Channel.BUFFERED)
    val events: Flow<TransactionsEvent> = _events.receiveAsFlow()

    private var observationJob: Job? = null
    private var filterJob: Job? = null
    private var mappedTransactions: MappedTransactions? = null

    init {
        refresh()
    }

    /** 重新订阅当前月份；Room Flow 会继续自动响应后续新增和删除。 */
    fun refresh() {
        observationJob?.cancel()
        filterJob?.cancel()
        mappedTransactions = null
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        observationJob = viewModelScope.launch {
            try {
                val period = selectedMonth.toMonthPeriod(zoneId)
                combine(
                    getMonthlyTransactions.observe(period),
                    categoryRepository.observeAll(),
                ) { transactions, categories ->
                    val categoriesById = categories.associateBy(Category::id)
                    MappedTransactions(
                        items = transactions.map { transaction ->
                            transaction.toUiState(categoriesById[transaction.categoryId])
                        },
                        categoryOptions = categories
                            .sortedBy(Category::name)
                            .map { category ->
                                TransactionFilterOption(category.id, category.name)
                            },
                        accountOptions = transactions.mapNotNull(Transaction::accountId)
                            .distinct()
                            .sorted()
                            .map { TransactionFilterOption(it, it) },
                    )
                }
                    // 日期格式化和 Entity 对应的 UI 映射不会阻塞 LazyColumn 的主线程绘制。
                    .flowOn(Dispatchers.Default)
                    .collect { mapped ->
                        mappedTransactions = mapped
                        applyFilters()
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                filterJob?.cancel()
                mappedTransactions = null
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "流水加载失败，请稍后重试")
                }
            }
        }
    }

    fun showPreviousMonth() {
        selectMonth(selectedMonth.minusMonths(1))
    }

    fun showNextMonth() {
        nextTransactionMonthOrNull(selectedMonth, latestAllowedMonth)?.let(::selectMonth)
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilters(delayForTyping = query.isNotBlank())
    }

    fun selectType(type: TransactionType?) {
        _uiState.update { it.copy(selectedType = type) }
        applyFilters()
    }

    fun selectCategory(categoryId: String?) {
        _uiState.update { it.copy(selectedCategoryId = categoryId) }
        applyFilters()
    }

    fun selectAccount(account: AccountFilter) {
        _uiState.update { it.copy(selectedAccount = account) }
        applyFilters()
    }

    fun selectAmountSort(sort: AmountSort) {
        if (_uiState.value.amountSort == sort) return
        _uiState.update { it.copy(amountSort = sort) }
        applyFilters()
    }

    fun clearFilters() {
        _uiState.update {
            it.copy(
                searchQuery = "",
                selectedType = null,
                selectedCategoryId = null,
                selectedAccount = AccountFilter.All,
            )
        }
        applyFilters()
    }

    fun requestDelete(transactionId: String) {
        val item = _uiState.value.items.firstOrNull { it.id == transactionId } ?: return
        _uiState.update { it.copy(pendingDeleteItem = item) }
    }


    fun dismissDelete() {
        if (!_uiState.value.isDeleting) {
            _uiState.update { it.copy(pendingDeleteItem = null) }
        }
    }

    fun confirmDelete() {
        val item = _uiState.value.pendingDeleteItem ?: return
        if (_uiState.value.isDeleting) return

        _uiState.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            try {
                val deleted = transactionRepository.delete(item.id)
                _uiState.update { it.copy(pendingDeleteItem = null, isDeleting = false) }
                if (!deleted) {
                    _events.send(TransactionsEvent.ShowMessage("这笔流水已不存在，列表已同步"))
                    refresh()
                } else {
                    _events.send(TransactionsEvent.ShowMessage("流水已删除"))
                }
                // 删除成功后不手动改列表，等待 Room Flow 发出数据库真实结果。
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(pendingDeleteItem = null, isDeleting = false) }
                _events.send(TransactionsEvent.ShowMessage("删除失败，请稍后重试"))
            }
        }
    }

    private fun selectMonth(month: YearMonth) {
        selectedMonth = minOf(month, latestAllowedMonth)
        _uiState.update {
            it.copy(
                monthLabel = monthFormatter.format(selectedMonth),
                canShowNextMonth = selectedMonth < latestAllowedMonth,
                pendingDeleteItem = null,
                isDeleting = false,
            )
        }
        refresh()
    }

    /** 搜索和排序使用最新快照，切月或继续输入时取消旧结果。 */
    private fun applyFilters(delayForTyping: Boolean = false) {
        val mapped = mappedTransactions ?: return
        val filters = _uiState.value
        val query = filters.searchQuery.trim()
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            if (delayForTyping) delay(100)
            val items = withContext(Dispatchers.Default) {
                val filtered = filterTransactionItems(
                    items = mapped.items,
                    searchQuery = query,
                    selectedType = filters.selectedType,
                    selectedCategoryId = filters.selectedCategoryId,
                    selectedAccount = filters.selectedAccount,
                )
                currentCoroutineContext().ensureActive()
                sortTransactionItems(filtered, filters.amountSort)
            }
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    items = items,
                    categoryOptions = mapped.categoryOptions,
                    accountOptions = mapped.accountOptions,
                    hasActiveFilters = query.isNotEmpty() ||
                        filters.selectedType != null ||
                        filters.selectedCategoryId != null ||
                        filters.selectedAccount != AccountFilter.All,
                    pendingDeleteItem = current.pendingDeleteItem?.takeIf { pending ->
                        items.any { it.id == pending.id }
                    },
                    errorMessage = null,
                )
            }
        }
    }

    private fun Transaction.toUiState(category: Category?): TransactionItemUiState {
        val localDateTime = java.time.Instant.ofEpochMilli(dateTime.toEpochMilliseconds())
            .atZone(zoneId)
        return TransactionItemUiState(
            id = id,
            categoryId = categoryId,
            categoryName = category?.name ?: "未知分类",
            categoryIconKey = category?.iconKey ?: "other",
            accountId = accountId,
            note = note,
            amountText = formatAmount(amount, type),
            dateTimeText = dateTimeFormatter.format(localDateTime),
            typeLabel = if (type == TransactionType.EXPENSE) "支出" else "收入",
            isExpense = type == TransactionType.EXPENSE,
            type = type,
            amountMinor = amount,
        )
    }

    private fun formatAmount(amount: Long, type: TransactionType): String {
        val sign = if (type == TransactionType.EXPENSE) "−" else "+"
        return sign + formatMoney(amount)
    }

    private companion object {
        val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "yyyy年M月",
            Locale.SIMPLIFIED_CHINESE,
        )
        val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "M月d日 HH:mm",
            Locale.SIMPLIFIED_CHINESE,
        )
    }
}

/** 流水页只能向前回到当月，避免空的未来月份被误认为当前数据。 */
internal fun nextTransactionMonthOrNull(
    selectedMonth: YearMonth,
    currentMonth: YearMonth,
): YearMonth? = if (selectedMonth >= currentMonth) {
    null
} else {
    minOf(selectedMonth.plusMonths(1), currentMonth)
}

private data class MappedTransactions(
    val items: List<TransactionItemUiState>,
    val categoryOptions: List<TransactionFilterOption>,
    val accountOptions: List<TransactionFilterOption>,
)

private val searchWhitespace = Regex("\\s+")

/** 搜索与收支、分类、账户筛选叠加使用。 */
internal fun filterTransactionItems(
    items: List<TransactionItemUiState>,
    searchQuery: String,
    selectedType: TransactionType?,
    selectedCategoryId: String?,
    selectedAccount: AccountFilter,
): List<TransactionItemUiState> {
    val terms = searchQuery.trim().splitToSequence(searchWhitespace).filter { it.isNotEmpty() }.toList()
    if (terms.isEmpty() && selectedType == null && selectedCategoryId == null && selectedAccount == AccountFilter.All) {
        return items
    }
    return items.filter { item ->
        terms.all { term ->
            item.note?.contains(term, ignoreCase = true) == true ||
                item.categoryName.contains(term, ignoreCase = true) ||
                item.accountId?.contains(term, ignoreCase = true) == true ||
                item.dateTimeText.contains(term, ignoreCase = true) ||
                item.amountText.contains(term, ignoreCase = true)
        } &&
            (selectedType == null || item.type == selectedType) &&
            (selectedCategoryId == null || item.categoryId == selectedCategoryId) &&
            when (selectedAccount) {
                AccountFilter.All -> true
                AccountFilter.Unspecified -> item.accountId == null
                is AccountFilter.Specific -> item.accountId == selectedAccount.accountId
            }
    }
}

/**
 * 对已过滤的月度流水按金额排序。
 *
 * 输入列表来自 Room 的时间倒序查询（时间相同再按 id 倒序），Kotlin 稳定排序会让金额相同的
 * 流水保持原有时间次序，因此金额排序也不会打乱“同一天内时间最新在前”的相对关系。
 */
internal fun sortTransactionItems(
    items: List<TransactionItemUiState>,
    amountSort: AmountSort,
): List<TransactionItemUiState> = when (amountSort) {
    AmountSort.TIME_NEWEST -> items
    AmountSort.AMOUNT_LARGEST ->
        items.sortedWith(compareByDescending<TransactionItemUiState> { it.amountMinor })
    AmountSort.AMOUNT_SMALLEST ->
        items.sortedWith(compareBy<TransactionItemUiState> { it.amountMinor })
}

package com.financeos.app.ui.viewmodel

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
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.Dispatchers

/** 流水列表单行展示状态。 */
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
)

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
    private var allItems: List<TransactionItemUiState> = emptyList()
    private var accountOptions: List<TransactionFilterOption> = emptyList()

    init {
        refresh()
    }

    /** 重新订阅当前月份；Room Flow 会继续自动响应后续新增和删除。 */
    fun refresh() {
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
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
                    _uiState.update { current ->
                        current.copy(categoryOptions = mapped.categoryOptions)
                    }
                    allItems = mapped.items
                    accountOptions = mapped.accountOptions
                    applyFilters(isLoading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
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
        applyFilters()
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
        allItems = emptyList()
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

    /** 在已映射的月度列表上组合条件，避免输入关键词时反复访问数据库或格式化日期。 */
    private fun applyFilters(isLoading: Boolean = _uiState.value.isLoading) {
        val state = _uiState.value
        val query = state.searchQuery.trim()
        val filteredItems = filterTransactionItems(
            items = allItems,
            searchQuery = query,
            selectedType = state.selectedType,
            selectedCategoryId = state.selectedCategoryId,
            selectedAccount = state.selectedAccount,
        )
        _uiState.update { current ->
            current.copy(
                isLoading = isLoading,
                items = filteredItems,
                accountOptions = accountOptions,
                hasActiveFilters = query.isNotEmpty() ||
                    current.selectedType != null ||
                    current.selectedCategoryId != null ||
                    current.selectedAccount != AccountFilter.All,
                pendingDeleteItem = current.pendingDeleteItem?.takeIf { pending ->
                    filteredItems.any { it.id == pending.id }
                },
                errorMessage = null,
            )
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

/** 组合备注、收支、分类和账户条件，供 ViewModel 与单元测试复用。 */
internal fun filterTransactionItems(
    items: List<TransactionItemUiState>,
    searchQuery: String,
    selectedType: TransactionType?,
    selectedCategoryId: String?,
    selectedAccount: AccountFilter,
): List<TransactionItemUiState> {
    val query = searchQuery.trim()
    return items.filter { item ->
        (query.isEmpty() || item.note?.contains(query, ignoreCase = true) == true) &&
            (selectedType == null || item.type == selectedType) &&
            (selectedCategoryId == null || item.categoryId == selectedCategoryId) &&
            when (selectedAccount) {
                AccountFilter.All -> true
                AccountFilter.Unspecified -> item.accountId == null
                is AccountFilter.Specific -> item.accountId == selectedAccount.accountId
            }
    }
}

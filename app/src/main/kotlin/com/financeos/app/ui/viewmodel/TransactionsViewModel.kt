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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 流水列表单行展示状态。 */
data class TransactionItemUiState(
    val id: String,
    val categoryName: String,
    val categoryIconKey: String,
    val note: String?,
    val amountText: String,
    val dateTimeText: String,
    val typeLabel: String,
    val isExpense: Boolean,
)

/** 流水列表页面状态。 */
data class TransactionsUiState(
    val monthLabel: String,
    val isLoading: Boolean = true,
    val items: List<TransactionItemUiState> = emptyList(),
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
) : ViewModel() {
    private var selectedMonth = initialMonth ?: YearMonth.now(zoneId)
    private val _uiState = MutableStateFlow(
        TransactionsUiState(monthLabel = monthFormatter.format(selectedMonth)),
    )
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private val _events = Channel<TransactionsEvent>(capacity = Channel.BUFFERED)
    val events: Flow<TransactionsEvent> = _events.receiveAsFlow()

    private var observationJob: Job? = null

    init {
        refresh()
    }

    /** 重新订阅当前月份；Room Flow 会继续自动响应后续新增和删除。 */
    fun refresh() {
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val categoriesById = categoryRepository.getAll().associateBy(Category::id)
                val period = selectedMonth.toMonthPeriod(zoneId)
                getMonthlyTransactions.observe(period).collect { transactions ->
                    val items = transactions.map { transaction ->
                        transaction.toUiState(categoriesById[transaction.categoryId])
                    }
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            items = items,
                            pendingDeleteItem = current.pendingDeleteItem?.takeIf { pending ->
                                items.any { it.id == pending.id }
                            },
                            errorMessage = null,
                        )
                    }
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
        selectMonth(selectedMonth.plusMonths(1))
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
        selectedMonth = month
        _uiState.update {
            it.copy(
                monthLabel = monthFormatter.format(month),
                pendingDeleteItem = null,
                isDeleting = false,
            )
        }
        refresh()
    }

    private fun Transaction.toUiState(category: Category?): TransactionItemUiState {
        val localDateTime = java.time.Instant.ofEpochMilli(dateTime.toEpochMilliseconds())
            .atZone(zoneId)
        return TransactionItemUiState(
            id = id,
            categoryName = category?.name ?: "未知分类",
            categoryIconKey = category?.iconKey ?: "other",
            note = note,
            amountText = formatAmount(amount, type),
            dateTimeText = dateTimeFormatter.format(localDateTime),
            typeLabel = if (type == TransactionType.EXPENSE) "支出" else "收入",
            isExpense = type == TransactionType.EXPENSE,
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

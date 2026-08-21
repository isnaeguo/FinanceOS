package com.financeos.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.repository.CategoryRepository
import com.financeos.shared.domain.repository.TransactionRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 流水列表单行展示状态。 */
data class TransactionItemUiState(
    val id: String,
    val categoryName: String,
    val note: String?,
    val amountText: String,
    val dateTimeText: String,
    val isExpense: Boolean,
)

/** 流水列表页面状态。 */
data class TransactionsUiState(
    val isLoading: Boolean = true,
    val items: List<TransactionItemUiState> = emptyList(),
    val errorMessage: String? = null,
)

/** 从业务 Repository 读取流水，并转换为 Android 页面可直接展示的状态。 */
class TransactionsViewModel(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /** 保存完成后显式刷新，确保一次性 Repository 读取也能立刻反映新增流水。 */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val categoryNames = categoryRepository.getAll().associate { it.id to it.name }
                val items = transactionRepository.getAll().map { transaction ->
                    transaction.toUiState(categoryNames[transaction.categoryId] ?: "未知分类")
                }
                _uiState.value = TransactionsUiState(isLoading = false, items = items)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "流水加载失败，请稍后重试")
                }
            }
        }
    }

    private fun Transaction.toUiState(categoryName: String): TransactionItemUiState {
        val localDateTime = java.time.Instant.ofEpochMilli(dateTime.toEpochMilliseconds())
            .atZone(ZoneId.systemDefault())
        return TransactionItemUiState(
            id = id,
            categoryName = categoryName,
            note = note,
            amountText = formatAmount(amount, type),
            dateTimeText = dateTimeFormatter.format(localDateTime),
            isExpense = type == TransactionType.EXPENSE,
        )
    }

    // Long 保存“分”，这里使用整数拆分格式化，避免展示阶段重新引入浮点误差。
    private fun formatAmount(amount: Long, type: TransactionType): String {
        val sign = if (type == TransactionType.EXPENSE) "−" else "+"
        return "$sign¥${amount / 100}.${(amount % 100).toString().padStart(2, '0')}"
    }

    private companion object {
        val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "M月d日 HH:mm",
            Locale.SIMPLIFIED_CHINESE,
        )
    }
}

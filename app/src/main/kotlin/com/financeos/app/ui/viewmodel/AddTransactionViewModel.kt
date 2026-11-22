package com.financeos.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.repository.CategoryRepository
import com.financeos.shared.domain.usecase.AddTransactionCommand
import com.financeos.shared.domain.usecase.AddTransactionUseCase
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** 记账页展示分类所需的最小信息。 */
data class CategoryOptionUiState(
    val id: String,
    val name: String,
)

/** 记账表单的完整可观察状态。 */
data class AddTransactionUiState(
    val amountInput: String = "",
    val type: TransactionType = TransactionType.EXPENSE,
    val categories: List<CategoryOptionUiState> = emptyList(),
    val selectedCategoryId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val latestAllowedDate: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val note: String = "",
    val account: String = "",
    val isLoaded: Boolean = true,
    val isLoadingCategories: Boolean = true,
    val isSaving: Boolean = false,
    val amountError: String? = null,
    val errorMessage: String? = null,
) {
    val canSave: Boolean
        get() = parseAmountInMinorUnits(amountInput) != null &&
            selectedCategoryId != null &&
            isTransactionDateAllowed(date, latestAllowedDate) &&
            isLoaded && !isLoadingCategories &&
            !isSaving
}

/** 只执行一次的记账页事件。 */
sealed interface AddTransactionEvent {
    data object Saved : AddTransactionEvent
}

/** 管理记账表单、调用领域 UseCase，并把一次性结果发送给导航层。 */
class AddTransactionViewModel(
    private val addTransactionUseCase: AddTransactionUseCase,
    private val categoryRepository: CategoryRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    currentDate: LocalDate? = null,
    private val editId: String? = null,
    private val loadTransaction: suspend (String) -> Transaction? = { null },
    private val updateTransaction: suspend (Transaction, Transaction) -> Boolean = { _, _ -> false },
) : ViewModel() {
    private val today = currentDate ?: LocalDate.now(zoneId)
    private val _uiState = MutableStateFlow(
        AddTransactionUiState(
            date = today,
            latestAllowedDate = today,
            time = LocalTime.now(zoneId).withSecond(0).withNano(0),
            isLoaded = editId == null,
        ),
    )
    val uiState: StateFlow<AddTransactionUiState> = _uiState.asStateFlow()

    // 保存成功属于一次性导航信号，不放入可重放的 UiState，避免旋转屏幕后重复返回。
    private val _events = Channel<AddTransactionEvent>(capacity = Channel.BUFFERED)
    val events: Flow<AddTransactionEvent> = _events.receiveAsFlow()

    private var allCategories: List<Category> = emptyList()
    private var originalTransaction: Transaction? = null

    init {
        loadForm()
    }

    fun onAmountChanged(rawInput: String) {
        val normalized = normalizeAmountInput(rawInput) ?: return
        _uiState.update {
            it.copy(
                amountInput = normalized,
                amountError = if (
                    normalized.isNotEmpty() && parseAmountInMinorUnits(normalized) == null
                ) {
                    "请输入有效金额，最多保留两位小数"
                } else {
                    null
                },
                errorMessage = null,
            )
        }
    }

    fun onTypeChanged(type: TransactionType) {
        _uiState.update { current ->
            val options = categoryOptionsFor(type)
            val selectedId = current.selectedCategoryId
                ?.takeIf { id -> options.any { it.id == id } }
                ?: options.firstOrNull()?.id
            current.copy(
                type = type,
                categories = options,
                selectedCategoryId = selectedId,
                errorMessage = null,
            )
        }
    }

    fun onCategorySelected(categoryId: String) {
        if (_uiState.value.categories.none { it.id == categoryId }) return
        _uiState.update { it.copy(selectedCategoryId = categoryId, errorMessage = null) }
    }

    fun onDateChanged(date: LocalDate) {
        if (!isTransactionDateAllowed(date, _uiState.value.latestAllowedDate)) {
            _uiState.update { it.copy(errorMessage = "记账日期不能晚于今天") }
            return
        }
        _uiState.update { it.copy(date = date, errorMessage = null) }
    }

    fun onTimeChanged(time: LocalTime) {
        _uiState.update {
            it.copy(time = time.withSecond(0).withNano(0), errorMessage = null)
        }
    }

    fun onNoteChanged(note: String) {
        _uiState.update {
            if (note.length <= MAX_NOTE_LENGTH || note.length < it.note.length) {
                it.copy(note = note, errorMessage = null)
            } else it
        }
    }

    fun onAccountChanged(account: String) {
        _uiState.update {
            if (account.length <= MAX_ACCOUNT_LENGTH || account.length < it.account.length) {
                it.copy(account = account, errorMessage = null)
            } else it
        }
    }

    fun retryCategories() {
        if (!_uiState.value.isLoadingCategories) loadForm()
    }

    fun save() {
        val current = _uiState.value
        if (current.isSaving || !current.isLoaded || current.isLoadingCategories) return

        val amount = parseAmountInMinorUnits(current.amountInput)
        if (amount == null) {
            _uiState.update {
                it.copy(amountError = "请输入大于 0 的有效金额，最多保留两位小数")
            }
            return
        }
        val categoryId = current.selectedCategoryId
        if (categoryId == null) {
            _uiState.update { it.copy(errorMessage = "请选择分类") }
            return
        }
        // UI 已禁用未来日期，保存前再校验一次，避免状态恢复或外部调用绕过限制。
        if (!isTransactionDateAllowed(current.date, current.latestAllowedDate)) {
            _uiState.update { it.copy(errorMessage = "记账日期不能晚于今天") }
            return
        }

        _uiState.update { it.copy(isSaving = true, amountError = null, errorMessage = null) }
        viewModelScope.launch {
            try {
                val original = originalTransaction
                val originalDateTime = original?.localDateTime()
                val localDateTime = LocalDateTime.of(current.date, current.time)
                val previousMinute = originalDateTime?.withSecond(0)?.withNano(0)
                val dateTime = if (original != null && previousMinute == localDateTime) {
                    original.dateTime
                } else {
                    if (zoneId.rules.getValidOffsets(localDateTime).isEmpty()) {
                        _uiState.update {
                            it.copy(isSaving = false, errorMessage = "所选时间因时区变更不存在，请改选其他时间。")
                        }
                        return@launch
                    }
                    val javaInstant = localDateTime.atZone(zoneId).toInstant()
                    Instant.fromEpochMilliseconds(javaInstant.toEpochMilli())
                }
                val note = current.note.trim().ifBlank { null }
                val accountId = current.account.trim().ifBlank { null }
                if (original == null) {
                    addTransactionUseCase(
                        AddTransactionCommand(
                            id = UUID.randomUUID().toString(),
                            amount = amount,
                            type = current.type,
                            categoryId = categoryId,
                            accountId = accountId,
                            dateTime = dateTime,
                            note = note,
                        ),
                    )
                } else {
                    val replacement = original.copy(
                        amount = amount,
                        type = current.type,
                        categoryId = categoryId,
                        accountId = accountId,
                        dateTime = dateTime,
                        note = note,
                    )
                    if (!updateTransaction(original, replacement)) {
                        _uiState.update {
                            it.copy(isSaving = false, isLoaded = false, errorMessage = "流水或分类已变更，请重新加载。")
                        }
                        return@launch
                    }
                }
                _events.send(AddTransactionEvent.Saved)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "金额或分类已发生变化，请检查后重试",
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "保存失败，请稍后重试",
                    )
                }
            }
        }
    }

    private fun loadForm() {
        _uiState.update {
            it.copy(isLoadingCategories = true, errorMessage = null)
        }
        viewModelScope.launch {
            try {
                val defaultOrder = DefaultCategories.all
                    .mapIndexed { index, category -> category.id to index }
                    .toMap()
                allCategories = categoryRepository.getAll().sortedWith(
                    compareBy<Category>(
                        { defaultOrder[it.id] ?: Int.MAX_VALUE },
                        { it.name },
                    ),
                )
                val existing = editId?.let { loadTransaction(it) }
                if (editId != null && (existing == null || existing.deletedAt != null)) {
                    _uiState.update {
                        it.copy(isLoadingCategories = false, isLoaded = false, errorMessage = "这笔流水已不存在。")
                    }
                    return@launch
                }
                originalTransaction = existing
                _uiState.update { current ->
                    val type = existing?.type ?: current.type
                    val options = categoryOptionsFor(type)
                    val chosenCategory = (existing?.categoryId ?: current.selectedCategoryId)
                        ?.takeIf { id -> options.any { it.id == id } }
                    val originalDateTime = existing?.localDateTime()
                    current.copy(
                        amountInput = existing?.amount?.let(::amountInputFromMinorUnits) ?: current.amountInput,
                        type = type,
                        date = originalDateTime?.toLocalDate() ?: current.date,
                        latestAllowedDate = maxOf(today, originalDateTime?.toLocalDate() ?: today),
                        time = originalDateTime?.toLocalTime()?.withSecond(0)?.withNano(0) ?: current.time,
                        note = if (existing == null) current.note else existing.note.orEmpty(),
                        account = if (existing == null) current.account else existing.accountId.orEmpty(),
                        categories = options,
                        selectedCategoryId = if (existing == null) chosenCategory ?: options.firstOrNull()?.id else chosenCategory,
                        isLoadingCategories = false,
                        isLoaded = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingCategories = false,
                        errorMessage = "分类加载失败，请重试",
                    )
                }
            }
        }
    }

    private fun Transaction.localDateTime(): LocalDateTime =
        java.time.Instant.ofEpochMilli(dateTime.toEpochMilliseconds()).atZone(zoneId).toLocalDateTime()

    private fun categoryOptionsFor(type: TransactionType): List<CategoryOptionUiState> =
        allCategories
            .filter { category -> category.type.accepts(type) }
            .map { category -> CategoryOptionUiState(category.id, category.name) }

    private fun CategoryType.accepts(transactionType: TransactionType): Boolean = when (this) {
        CategoryType.COMMON -> true
        CategoryType.INCOME -> transactionType == TransactionType.INCOME
        CategoryType.EXPENSE -> transactionType == TransactionType.EXPENSE
    }

    private companion object {
        const val MAX_NOTE_LENGTH = 200
        const val MAX_ACCOUNT_LENGTH = 100
    }
}

/** 今天可记录，但不允许创建任何未来日期的流水。 */
internal fun isTransactionDateAllowed(date: LocalDate, today: LocalDate): Boolean =
    !date.isAfter(today)

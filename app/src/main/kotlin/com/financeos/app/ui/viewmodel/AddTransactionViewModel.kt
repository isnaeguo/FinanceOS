package com.financeos.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.DefaultCategories
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
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val note: String = "",
    val isLoadingCategories: Boolean = true,
    val isSaving: Boolean = false,
    val amountError: String? = null,
    val errorMessage: String? = null,
) {
    val canSave: Boolean
        get() = parseAmountInMinorUnits(amountInput) != null &&
            selectedCategoryId != null &&
            !isLoadingCategories &&
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
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddTransactionUiState())
    val uiState: StateFlow<AddTransactionUiState> = _uiState.asStateFlow()

    // 保存成功属于一次性导航信号，不放入可重放的 UiState，避免旋转屏幕后重复返回。
    private val _events = Channel<AddTransactionEvent>(capacity = Channel.BUFFERED)
    val events: Flow<AddTransactionEvent> = _events.receiveAsFlow()

    private var allCategories: List<Category> = emptyList()

    init {
        loadCategories()
    }

    fun onAmountChanged(rawInput: String) {
        val normalized = normalizeAmountInput(rawInput) ?: return
        _uiState.update {
            it.copy(
                amountInput = normalized,
                amountError = if (
                    normalized.isNotEmpty() && parseAmountInMinorUnits(normalized) == null
                ) {
                    "金额必须大于 0"
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
        _uiState.update { it.copy(date = date, errorMessage = null) }
    }

    fun onTimeChanged(time: LocalTime) {
        _uiState.update {
            it.copy(time = time.withSecond(0).withNano(0), errorMessage = null)
        }
    }

    fun onNoteChanged(note: String) {
        if (note.length <= MAX_NOTE_LENGTH) {
            _uiState.update { it.copy(note = note, errorMessage = null) }
        }
    }

    fun save() {
        val current = _uiState.value
        if (current.isSaving) return

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

        _uiState.update { it.copy(isSaving = true, amountError = null, errorMessage = null) }
        viewModelScope.launch {
            try {
                val javaInstant = LocalDateTime.of(current.date, current.time)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                addTransactionUseCase(
                    AddTransactionCommand(
                        id = UUID.randomUUID().toString(),
                        amount = amount,
                        type = current.type,
                        categoryId = categoryId,
                        dateTime = Instant.fromEpochMilliseconds(javaInstant.toEpochMilli()),
                        note = current.note.trim().ifBlank { null },
                    ),
                )
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

    private fun loadCategories() {
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
                _uiState.update { current ->
                    val options = categoryOptionsFor(current.type)
                    current.copy(
                        categories = options,
                        selectedCategoryId = options.firstOrNull()?.id,
                        isLoadingCategories = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingCategories = false,
                        errorMessage = "分类加载失败，请返回后重试",
                    )
                }
            }
        }
    }

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
    }
}

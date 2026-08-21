package com.financeos.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.shared.domain.calculation.BudgetUsage
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.repository.BudgetRepository
import com.financeos.shared.domain.repository.CategoryRepository
import com.financeos.shared.domain.usecase.GetBudgetStatusUseCase
import com.financeos.shared.domain.usecase.GetMonthlyTransactionsUseCase
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
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

/** UseCase 预算结果对应的 Android 展示状态。 */
data class BudgetUsageUiState(
    val hasBudget: Boolean,
    val amountLimitMinor: Long?,
    val amountLimitText: String,
    val amountUsedText: String,
    val amountRemainingText: String,
    val usageRatioText: String,
    val progress: Float,
    val statusText: String,
    val isOverBudget: Boolean,
)

/** 一项已配置的分类预算。 */
data class CategoryBudgetUiState(
    val categoryId: String,
    val categoryName: String,
    val categoryIconKey: String,
    val usage: BudgetUsageUiState,
)

/** 新增分类预算时可选择的分类。 */
data class BudgetCategoryOptionUiState(
    val id: String,
    val name: String,
)

/** 预算编辑对话框状态。 */
data class BudgetEditorUiState(
    val title: String,
    val isCategoryBudget: Boolean,
    val isNewCategoryBudget: Boolean,
    val selectedCategoryId: String?,
    val categoryOptions: List<BudgetCategoryOptionUiState>,
    val amountInput: String,
    val amountError: String? = null,
    val saveError: String? = null,
    val isSaving: Boolean = false,
) {
    val canSave: Boolean
        get() = parseAmountInMinorUnits(amountInput, allowZero = true) != null &&
            (!isCategoryBudget || selectedCategoryId != null) &&
            !isSaving
}

/** 预算页只允许在当前月和紧邻的下个月之间切换。 */
enum class BudgetMonthSelection(val label: String) {
    CURRENT("本月"),
    NEXT("下月"),
}

/** 当前选定月份的预算页面状态。 */
data class BudgetUiState(
    val monthLabel: String,
    val monthSelection: BudgetMonthSelection = BudgetMonthSelection.CURRENT,
    val isLoading: Boolean = true,
    val total: BudgetUsageUiState? = null,
    val categoryBudgets: List<CategoryBudgetUiState> = emptyList(),
    val canAddCategoryBudget: Boolean = false,
    val editor: BudgetEditorUiState? = null,
    val errorMessage: String? = null,
)

/** 预算页只执行一次的轻量操作反馈。 */
sealed interface BudgetEvent {
    data class ShowMessage(val message: String) : BudgetEvent
}

/** 管理本月或下月预算结果和编辑状态，所有预算指标均来自 [GetBudgetStatusUseCase]。 */
class BudgetViewModel(
    private val getBudgetStatus: GetBudgetStatusUseCase,
    private val getMonthlyTransactions: GetMonthlyTransactionsUseCase,
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    currentMonth: YearMonth? = null,
) : ViewModel() {
    private val baseMonth = currentMonth ?: YearMonth.now(zoneId)
    private var monthSelection = BudgetMonthSelection.CURRENT
    private var selectedMonth = monthSelection.resolve(baseMonth)
    private val _uiState = MutableStateFlow(
        BudgetUiState(monthLabel = monthFormatter.format(selectedMonth)),
    )
    val uiState: StateFlow<BudgetUiState> = _uiState.asStateFlow()
    private val _events = Channel<BudgetEvent>(capacity = Channel.BUFFERED)
    val events: Flow<BudgetEvent> = _events.receiveAsFlow()

    private var expenseCategories: List<Category> = emptyList()
    private var observationJob: Job? = null

    init {
        refresh()
    }

    fun selectMonth(selection: BudgetMonthSelection) {
        if (selection == monthSelection) return
        monthSelection = selection
        selectedMonth = selection.resolve(baseMonth)
        expenseCategories = emptyList()
        _uiState.update {
            BudgetUiState(
                monthLabel = monthFormatter.format(selectedMonth),
                monthSelection = selection,
            )
        }
        refresh()
    }

    /** 观察同月流水和预算，并直接复用同一批快照完成计算。 */
    fun refresh() {
        val period = selectedMonth.toMonthPeriod(zoneId)
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                combine(
                    getMonthlyTransactions.observe(period),
                    budgetRepository.observeByMonth(period.month),
                    categoryRepository.observeAll(),
                ) { transactions, budgets, categories ->
                    val filteredCategories = categories.filter {
                        it.type == CategoryType.EXPENSE || it.type == CategoryType.COMMON
                    }
                    val status = getBudgetStatus.calculate(transactions, budgets)
                    BudgetSnapshot(
                        expenseCategories = filteredCategories,
                        total = status.total.toUiState(),
                        categoryBudgets = filteredCategories.mapNotNull { category ->
                            val usage = status.categories[category.id] ?: return@mapNotNull null
                            CategoryBudgetUiState(
                                categoryId = category.id,
                                categoryName = category.name,
                                categoryIconKey = category.iconKey,
                                usage = usage.toUiState(),
                            )
                        },
                    )
                }
                    // 月流水汇总和展示格式化不占用主线程，避免预算进度更新时影响绘制。
                    .flowOn(Dispatchers.Default)
                    .collect { snapshot ->
                        expenseCategories = snapshot.expenseCategories
                        _uiState.update { current ->
                            current.copy(
                                isLoading = false,
                                total = snapshot.total,
                                categoryBudgets = snapshot.categoryBudgets,
                                canAddCategoryBudget =
                                    snapshot.categoryBudgets.size < snapshot.expenseCategories.size,
                                errorMessage = null,
                            )
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "预算加载失败，请稍后重试")
                }
            }
        }
    }

    fun openTotalBudgetEditor() {
        val total = _uiState.value.total ?: return
        _uiState.update {
            it.copy(
                editor = BudgetEditorUiState(
                    title = if (total.hasBudget) {
                        "修改${monthSelection.label}总预算"
                    } else {
                        "设置${monthSelection.label}总预算"
                    },
                    isCategoryBudget = false,
                    isNewCategoryBudget = false,
                    selectedCategoryId = null,
                    categoryOptions = emptyList(),
                    amountInput = total.amountLimitMinor?.toAmountInput() ?: "",
                ),
            )
        }
    }

    fun openNewCategoryBudgetEditor() {
        val configuredIds = _uiState.value.categoryBudgets.mapTo(mutableSetOf()) { it.categoryId }
        val options = expenseCategories
            .filterNot { it.id in configuredIds }
            .map { BudgetCategoryOptionUiState(it.id, it.name) }
        if (options.isEmpty()) return

        _uiState.update {
            it.copy(
                editor = BudgetEditorUiState(
                    title = "新增${monthSelection.label}分类预算",
                    isCategoryBudget = true,
                    isNewCategoryBudget = true,
                    selectedCategoryId = options.first().id,
                    categoryOptions = options,
                    amountInput = "",
                ),
            )
        }
    }

    fun openCategoryBudgetEditor(categoryId: String) {
        val categoryBudget = _uiState.value.categoryBudgets
            .firstOrNull { it.categoryId == categoryId }
            ?: return
        _uiState.update {
            it.copy(
                editor = BudgetEditorUiState(
                    title = "修改${monthSelection.label}${categoryBudget.categoryName}预算",
                    isCategoryBudget = true,
                    isNewCategoryBudget = false,
                    selectedCategoryId = categoryId,
                    categoryOptions = emptyList(),
                    amountInput = categoryBudget.usage.amountLimitMinor?.toAmountInput() ?: "",
                ),
            )
        }
    }

    fun onEditorCategorySelected(categoryId: String) {
        _uiState.update { current ->
            val editor = current.editor ?: return@update current
            if (!editor.isNewCategoryBudget || editor.categoryOptions.none { it.id == categoryId }) {
                return@update current
            }
            current.copy(editor = editor.copy(selectedCategoryId = categoryId, saveError = null))
        }
    }

    fun onEditorAmountChanged(rawInput: String) {
        val normalized = normalizeAmountInput(rawInput) ?: return
        _uiState.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(
                editor = editor.copy(
                    amountInput = normalized,
                    amountError = if (
                        normalized.isNotEmpty() &&
                        parseAmountInMinorUnits(normalized, allowZero = true) == null
                    ) {
                        "请输入有效金额，最多保留两位小数"
                    } else {
                        null
                    },
                    saveError = null,
                ),
            )
        }
    }

    fun dismissEditor() {
        if (_uiState.value.editor?.isSaving != true) {
            _uiState.update { it.copy(editor = null) }
        }
    }

    fun saveEditor() {
        val editor = _uiState.value.editor ?: return
        if (editor.isSaving) return

        val amountLimit = parseAmountInMinorUnits(editor.amountInput, allowZero = true)
        if (amountLimit == null) {
            updateEditor { it.copy(amountError = "请输入有效金额，最多保留两位小数") }
            return
        }
        val categoryId = editor.selectedCategoryId.takeIf { editor.isCategoryBudget }
        if (editor.isCategoryBudget && categoryId == null) {
            updateEditor { it.copy(saveError = "请选择分类") }
            return
        }

        updateEditor { it.copy(isSaving = true, amountError = null, saveError = null) }
        // 保存前固定目标月份，保证异步写入不会因页面状态变化而落到其他月份。
        val targetMonth = selectedMonth.toMonthPeriod(zoneId).month
        val targetMonthLabel = monthSelection.label
        viewModelScope.launch {
            try {
                val existing = budgetRepository.get(targetMonth, categoryId)
                budgetRepository.save(
                    Budget(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        month = targetMonth,
                        amountLimit = amountLimit,
                        categoryId = categoryId,
                    ),
                )
                _uiState.update { it.copy(editor = null) }
                _events.send(BudgetEvent.ShowMessage("${targetMonthLabel}预算已更新"))
                // Room 的预算 Flow 会触发统一刷新，避免保存路径手动拼装页面结果。
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateEditor {
                    it.copy(isSaving = false, saveError = "预算保存失败，请稍后重试")
                }
            }
        }
    }

    private fun updateEditor(transform: (BudgetEditorUiState) -> BudgetEditorUiState) {
        _uiState.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = transform(editor))
        }
    }

    private fun BudgetUsage.toUiState(): BudgetUsageUiState {
        val remaining = amountRemaining
        val ratio = usageRatio
        val remainingText = when {
            remaining == null -> "未设置"
            remaining < 0L -> "超出 ${formatMoney(abs(remaining))}"
            else -> formatMoney(remaining)
        }
        val ratioText = when {
            ratio != null -> "${(ratio * 100.0).roundToInt()}%"
            hasBudget -> "比例不可计算"
            else -> "未设置"
        }
        return BudgetUsageUiState(
            hasBudget = hasBudget,
            amountLimitMinor = amountLimit,
            amountLimitText = amountLimit?.let(::formatMoney) ?: "未设置",
            amountUsedText = formatMoney(amountUsed),
            amountRemainingText = remainingText,
            usageRatioText = ratioText,
            progress = ratio?.toFloat()?.coerceIn(0f, 1f)
                ?: if (isOverBudget) 1f else 0f,
            statusText = when {
                isOverBudget -> "已超预算，${remainingText}"
                hasBudget -> "预算范围内"
                else -> "尚未设置预算"
            },
            isOverBudget = isOverBudget,
        )
    }

    private fun Long.toAmountInput(): String =
        "${this / 100}.${(this % 100).toString().padStart(2, '0')}"

    private companion object {
        val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "yyyy年M月",
            Locale.SIMPLIFIED_CHINESE,
        )
    }
}

/** 把受限的页面选择映射为唯一允许管理的预算月份。 */
internal fun BudgetMonthSelection.resolve(baseMonth: YearMonth): YearMonth = when (this) {
    BudgetMonthSelection.CURRENT -> baseMonth
    BudgetMonthSelection.NEXT -> baseMonth.plusMonths(1)
}

private data class BudgetSnapshot(
    val expenseCategories: List<Category>,
    val total: BudgetUsageUiState,
    val categoryBudgets: List<CategoryBudgetUiState>,
)

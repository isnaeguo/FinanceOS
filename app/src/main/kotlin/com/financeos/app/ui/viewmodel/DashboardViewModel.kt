package com.financeos.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.repository.BudgetRepository
import com.financeos.shared.domain.repository.CategoryRepository
import com.financeos.shared.domain.usecase.CalculateDailyAvailableBudgetUseCase
import com.financeos.shared.domain.usecase.CalculateDailyExpenseUseCase
import com.financeos.shared.domain.usecase.DailyAvailableBudget
import com.financeos.shared.domain.usecase.GetBudgetStatusUseCase
import com.financeos.shared.domain.usecase.GetMonthlySummaryUseCase
import com.financeos.shared.domain.usecase.GetMonthlyTransactionsUseCase
import com.financeos.shared.domain.usecase.MonthlyBudgetStatus
import com.financeos.shared.domain.usecase.MonthlySummary
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Dashboard 中一项主要分类消费。 */
data class DashboardCategoryUiState(
    val categoryId: String,
    val categoryName: String,
    val categoryIconKey: String,
    val amountText: String,
    val shareText: String,
    val progress: Float,
)

/** Dashboard 最近流水单行状态。 */
data class DashboardTransactionUiState(
    val id: String,
    val categoryName: String,
    val categoryIconKey: String,
    val note: String?,
    val amountText: String,
    val typeLabel: String,
    val dateTimeText: String,
    val isExpense: Boolean,
)

/** 首页第一屏及后续列表所需的完整聚合状态。 */
data class DashboardUiState(
    val monthLabel: String,
    val isLoading: Boolean = true,
    val monthlyExpenseText: String = "¥0.00",
    val dailyExpenseText: String = "¥0.00",
    val monthlyIncomeText: String = "¥0.00",
    val remainingBudgetText: String = "未设置",
    val dailyAvailableText: String = "未设置",
    val dailyAvailableExplanation: String =
        "设置月总预算后显示；每天零点重新分配，当天保持不变，但不是硬限制。",
    val hasBudget: Boolean = false,
    val budgetProgress: Float = 0f,
    val budgetProgressText: String = "尚未设置月总预算",
    val isOverBudget: Boolean = false,
    val topCategories: List<DashboardCategoryUiState> = emptyList(),
    val recentTransactions: List<DashboardTransactionUiState> = emptyList(),
    val errorMessage: String? = null,
)

/** 调用现有 UseCase 聚合 Dashboard；Composable 不参与任何财务计算。 */
class DashboardViewModel(
    private val getMonthlySummary: GetMonthlySummaryUseCase,
    private val getBudgetStatus: GetBudgetStatusUseCase,
    private val calculateDailyAvailableBudget: CalculateDailyAvailableBudgetUseCase,
    private val calculateDailyExpense: CalculateDailyExpenseUseCase,
    private val getMonthlyTransactions: GetMonthlyTransactionsUseCase,
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate? = null,
) : ViewModel() {
    private val fixedDate = today
    private val _uiState = MutableStateFlow(
        DashboardUiState(monthLabel = monthFormatter.format(YearMonth.from(currentDate()))),
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var observationJob: Job? = null

    init {
        refresh()
    }

    /** 重新订阅设备当前月份；流水或预算变化只作为重新聚合信号。 */
    fun refresh() {
        val currentDate = currentDate()
        val currentMonth = YearMonth.from(currentDate)
        val period = currentMonth.toMonthPeriod(zoneId)
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val categoriesById = categoryRepository.getAll().associateBy(Category::id)
                combine(
                    getMonthlyTransactions.observe(period),
                    budgetRepository.observeByMonth(period.month),
                ) { _, _ -> Unit }.collect {
                    loadDashboard(
                        categoriesById = categoriesById,
                        currentDate = currentDate,
                        currentMonth = currentMonth,
                        period = period,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "首页数据加载失败，请稍后重试")
                }
            }
        }
    }

    private suspend fun loadDashboard(
        categoriesById: Map<String, Category>,
        currentDate: LocalDate,
        currentMonth: YearMonth,
        period: MonthPeriod,
    ) {
        val summary = getMonthlySummary(period)
        val budgetStatus = getBudgetStatus(period)
        val transactions = getMonthlyTransactions(period)
        val startOfToday = Instant.fromEpochMilliseconds(
            currentDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        )
        val startOfTomorrow = Instant.fromEpochMilliseconds(
            currentDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
        )
        val dailyAvailable = calculateDailyAvailableBudget(
            period = period,
            currentDayOfMonth = currentDate.dayOfMonth,
            startOfToday = startOfToday,
        )
        val dailyExpense = calculateDailyExpense(
            transactions = transactions,
            startOfDayInclusive = startOfToday,
            startOfNextDayExclusive = startOfTomorrow,
        )

        // 这里仅把多个 UseCase 结果组织成页面层级，不重新定义预算或收支业务规则。
        _uiState.value = buildDashboardUiState(
            monthLabel = monthFormatter.format(currentMonth),
            summary = summary,
            dailyExpense = dailyExpense,
            budgetStatus = budgetStatus,
            dailyAvailable = dailyAvailable,
            transactions = transactions,
            categoriesById = categoriesById,
            zoneId = zoneId,
        )
    }

    private fun currentDate(): LocalDate = fixedDate ?: LocalDate.now(zoneId)
}

/** 将已计算好的业务结果转换为稳定、可测试的 Dashboard 展示状态。 */
internal fun buildDashboardUiState(
    monthLabel: String,
    summary: MonthlySummary,
    dailyExpense: Long,
    budgetStatus: MonthlyBudgetStatus,
    dailyAvailable: DailyAvailableBudget?,
    transactions: List<Transaction>,
    categoriesById: Map<String, Category>,
    zoneId: ZoneId,
): DashboardUiState {
    val totalBudget = budgetStatus.total
    val remaining = totalBudget.amountRemaining
    val ratio = totalBudget.usageRatio
    val topCategories = summary.expensesByCategory.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(MAX_TOP_CATEGORIES)
        .map { (categoryId, amount) ->
            val category = categoriesById[categoryId]
            val share = if (summary.totalExpense == 0L) {
                0.0
            } else {
                amount.toDouble() / summary.totalExpense.toDouble()
            }
            DashboardCategoryUiState(
                categoryId = categoryId,
                categoryName = category?.name ?: "未知分类",
                categoryIconKey = category?.iconKey ?: "other",
                amountText = formatMoney(amount),
                shareText = "${(share * 100.0).roundToInt()}%",
                progress = share.toFloat().coerceIn(0f, 1f),
            )
        }
    val recentTransactions = transactions
        .sortedWith(compareByDescending<Transaction> { it.dateTime }.thenByDescending { it.id })
        .take(MAX_RECENT_TRANSACTIONS)
        .map { transaction ->
            val category = categoriesById[transaction.categoryId]
            val localDateTime = java.time.Instant
                .ofEpochMilli(transaction.dateTime.toEpochMilliseconds())
                .atZone(zoneId)
            val isExpense = transaction.type == TransactionType.EXPENSE
            DashboardTransactionUiState(
                id = transaction.id,
                categoryName = category?.name ?: "未知分类",
                categoryIconKey = category?.iconKey ?: "other",
                note = transaction.note,
                amountText = (if (isExpense) "−" else "+") +
                    formatMoney(transaction.amount),
                typeLabel = if (isExpense) "支出" else "收入",
                dateTimeText = transactionTimeFormatter.format(localDateTime),
                isExpense = isExpense,
            )
        }

    return DashboardUiState(
        monthLabel = monthLabel,
        isLoading = false,
        monthlyExpenseText = formatMoney(summary.totalExpense),
        dailyExpenseText = formatMoney(dailyExpense),
        monthlyIncomeText = formatMoney(summary.totalIncome),
        remainingBudgetText = when {
            remaining == null -> "未设置"
            remaining < 0L -> "超出 ${formatMoney(abs(remaining))}"
            else -> formatMoney(remaining)
        },
        dailyAvailableText = dailyAvailable?.dailyAmount?.let(::formatMoney) ?: "未设置",
        dailyAvailableExplanation = dailyAvailable?.let {
            "按今日零点时的剩余预算平均分到含今天在内的 ${it.remainingDays} 天；" +
                "当天记账不会改变此数值，仅供参考，不是硬限制。"
        } ?: "设置月总预算后显示；每天零点重新分配，当天保持不变，但不是硬限制。",
        hasBudget = totalBudget.hasBudget,
        budgetProgress = ratio?.toFloat()?.coerceIn(0f, 1f)
            ?: if (totalBudget.isOverBudget) 1f else 0f,
        budgetProgressText = when {
            totalBudget.isOverBudget && remaining != null ->
                "已超预算 ${formatMoney(abs(remaining))}"
            ratio != null -> "已使用 ${(ratio * 100.0).roundToInt()}%"
            totalBudget.hasBudget -> "预算为零，使用比例不可计算"
            else -> "尚未设置月总预算"
        },
        isOverBudget = totalBudget.isOverBudget,
        topCategories = topCategories,
        recentTransactions = recentTransactions,
    )
}

private const val MAX_TOP_CATEGORIES = 3
private const val MAX_RECENT_TRANSACTIONS = 5
private val monthFormatter = DateTimeFormatter.ofPattern("yyyy年M月", Locale.SIMPLIFIED_CHINESE)
private val transactionTimeFormatter = DateTimeFormatter.ofPattern(
    "M月d日 HH:mm",
    Locale.SIMPLIFIED_CHINESE,
)

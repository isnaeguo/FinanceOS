package com.financeos.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.financeos.app.MainActivity
import com.financeos.app.R
import com.financeos.app.data.FinanceDataBridge
import com.financeos.app.ui.viewmodel.toMonthPeriod
import com.financeos.shared.domain.usecase.CalculateDailyAvailableBudgetUseCase
import com.financeos.shared.domain.usecase.GetMonthlySummaryUseCase
import com.financeos.shared.domain.usecase.GetMonthlyTransactionsUseCase
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.time.Instant

/** 一次小组件渲染所需的展示数据，全部已格式化为中文金额文本。 */
internal data class FinanceWidgetData(
    val monthlyUsedText: String,
    val dailyAvailableText: String,
    val remainingText: String,
    val hasBudget: Boolean,
    val overBudget: Boolean,
    val failed: Boolean = false,
) {
    companion object {
        fun failed(): FinanceWidgetData = FinanceWidgetData(
            monthlyUsedText = "",
            dailyAvailableText = "",
            remainingText = "",
            hasBudget = false,
            overBudget = false,
            failed = true,
        )
    }
}

/**
 * 小组件纯逻辑：读取当月数据并构建 RemoteViews。
 *
 * 计算口径与首页 Dashboard 保持一致（参考 DashboardViewModel.buildDashboardSnapshot）：
 * 当月流水按用户时区的月份半开区间读取；本月已用为当月支出合计；本月剩余用月总预算扣减；
 * 每日可用采用 CalculateDailyAvailableBudgetUseCase 的纯计算语义（含今天）。
 */
internal object FinanceWidgetUpdater {

    /** 宽度达到该 dp 时切换到 4x2 横排布局。 */
    const val WIDE_LAYOUT_MIN_WIDTH_DP = 220

    suspend fun loadData(context: Context): FinanceWidgetData {
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val period = YearMonth.from(today).toMonthPeriod(zoneId)
        val bridge = FinanceDataBridge.get(context)

        val getMonthlyTransactions = GetMonthlyTransactionsUseCase(bridge.transactionRepository)
        val transactions = bridge.transactionRepository.getByMonth(
            startInclusive = period.startInclusive,
            endExclusive = period.endExclusive,
        )
        val summary = GetMonthlySummaryUseCase(getMonthlyTransactions).calculate(transactions)
        val used = summary.totalExpense
        val totalBudget = bridge.budgetRepository.get(period.month, categoryId = null)
        val remaining = totalBudget?.let { it.amountLimit - used }
        val hasBudget = totalBudget != null

        val startOfToday = Instant.fromEpochMilliseconds(
            today.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        )
        val dailyAvailable = CalculateDailyAvailableBudgetUseCase(
            budgetRepository = bridge.budgetRepository,
            getMonthlyTransactions = getMonthlyTransactions,
        ).calculate(
            period = period,
            currentDayOfMonth = today.dayOfMonth,
            startOfToday = startOfToday,
            totalBudget = totalBudget,
            transactions = transactions,
        )

        return FinanceWidgetData(
            monthlyUsedText = formatMoneyWholeYuan(used),
            dailyAvailableText = when {
                !hasBudget -> "未设预算"
                else -> formatMoneyWholeYuan(dailyAvailable?.dailyAmount ?: 0L)
            },
            remainingText = if (!hasBudget) {
                "未设预算"
            } else {
                // 已设预算时 remaining 一定非空，这里兜底以避免空安全误判。
                val remainingAfterBudget = remaining ?: 0L
                if (remainingAfterBudget < 0L) {
                    "-" + formatMoneyWholeYuan(-remainingAfterBudget)
                } else {
                    formatMoneyWholeYuan(remainingAfterBudget)
                }
            },
            hasBudget = hasBudget,
            overBudget = remaining != null && remaining < 0L,
        )
    }

    /** 按小组件当前宽度选择布局并填充文本；点击整卡打开 MainActivity。 */
    fun buildRemoteViews(
        context: Context,
        minWidthDp: Int,
        data: FinanceWidgetData,
    ): RemoteViews {
        val layout = if (minWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP) {
            R.layout.widget_finance_wide
        } else {
            R.layout.widget_finance_compact
        }
        val views = RemoteViews(context.packageName, layout)

        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent)

        if (data.failed) {
            views.setViewVisibility(R.id.widget_error, View.VISIBLE)
            views.setViewVisibility(R.id.widget_content, View.GONE)
            return views
        }

        views.setViewVisibility(R.id.widget_error, View.GONE)
        views.setViewVisibility(R.id.widget_content, View.VISIBLE)
        views.setTextViewText(R.id.widget_monthly_used, data.monthlyUsedText)
        views.setTextViewText(R.id.widget_daily_value, data.dailyAvailableText)
        views.setTextViewText(R.id.widget_remaining_value, data.remainingText)

        val mutedColor = context.getColor(R.color.widget_label_text)
        val accentColor = context.getColor(R.color.widget_accent_text)
        val dangerColor = context.getColor(R.color.widget_danger_text)
        val valueColor = context.getColor(R.color.widget_value_text)

        views.setTextColor(R.id.widget_monthly_used, valueColor)
        views.setTextColor(
            R.id.widget_daily_value,
            if (data.overBudget) dangerColor else accentColor,
        )
        views.setTextColor(
            R.id.widget_remaining_value,
            when {
                !data.hasBudget -> mutedColor
                data.overBudget -> dangerColor
                else -> accentColor
            },
        )
        return views
    }

/** 小组件金额：四舍五入到整数“元”，千分位显示，节省 2x1 空间。 */
private fun formatMoneyWholeYuan(minor: Long): String {
    val sign = if (minor < 0) "-" else ""
    val magnitude = kotlin.math.abs(minor)
    val rounded = (magnitude + 50) / 100
    val grouped = rounded.toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
    return "$sign¥$grouped"
}
}

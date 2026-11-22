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
import com.financeos.shared.data.local.repository.LocalFinanceDataRepository
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
 * 当月已用为支出合计；每日可用使用今天零点前的支出预算。
 */
internal object FinanceWidgetUpdater {

    /** 宽度达到该 dp 时切换到 4x2 横排布局。 */
    const val WIDE_LAYOUT_MIN_WIDTH_DP = 220

    suspend fun loadData(context: Context): FinanceWidgetData {
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val period = YearMonth.from(today).toMonthPeriod(zoneId)
        val bridge = FinanceDataBridge.get(context)

        val startOfToday = Instant.fromEpochMilliseconds(
            today.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        )
        val overview = LocalFinanceDataRepository(bridge.database).monthlyBudgetOverview(
            period = period,
            dayOfMonth = today.dayOfMonth,
            startOfToday = startOfToday,
        )

        return FinanceWidgetData(
            monthlyUsedText = formatMoneyWholeYuan(overview.amountUsed),
            dailyAvailableText = overview.dailyAvailable?.let(::formatMoneyWholeYuan) ?: "未设预算",
            remainingText = overview.amountRemaining?.let(::formatMoneyWholeYuan) ?: "未设预算",
            hasBudget = overview.amountRemaining != null,
            overBudget = overview.isOverBudget,
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
        val yuan = kotlin.math.abs(minor / 100L)
        val rounded = yuan + (kotlin.math.abs(minor % 100L) + 50L) / 100L
        val grouped = rounded.toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
        return "$sign¥$grouped"
    }
}

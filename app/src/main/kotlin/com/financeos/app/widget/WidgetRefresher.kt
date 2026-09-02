package com.financeos.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.financeos.app.data.FinanceDataBridge
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * App 侧监听数据库变更，任何流水/预算/分类写入成功后都会广播触发小组件即时刷新，
 * 让记账后小组件“秒级”反映，而不是等系统 30 分钟周期。
 */
object WidgetRefresher {
    @Volatile
    private var job: Job? = null

    fun ensure(context: Context) {
        if (job != null) return
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        job = scope.launch {
            val database = FinanceDataBridge.get(appContext).database
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val start = calendar.timeInMillis
            calendar.add(Calendar.MONTH, 1)
            val end = calendar.timeInMillis
            val year = Calendar.getInstance().get(Calendar.YEAR)
            val month = Calendar.getInstance().get(Calendar.MONTH) + 1

            val transactions = database.transactionDao().observeByPeriod(start, end)
            val budgets = database.budgetDao().observeByMonth(year, month)
            val categories = database.categoryDao().observeAll()

            var isFirst = true
            combine(transactions, budgets, categories) { _, _, _ -> Unit }
                .collect {
                    if (isFirst) {
                        isFirst = false
                    } else {
                        requestRefresh(appContext)
                    }
                }
        }
    }

    /** 显式通知小组件立即刷新（等效触发一次 onUpdate）。 */
    fun requestRefresh(context: Context) {
        val intent = Intent(context, FinanceWidgetProvider::class.java)
            .setAction(FinanceWidgetProvider.ACTION_REFRESH)
        context.sendBroadcast(intent)
    }

    fun hasAnyWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(
            ComponentName(context, FinanceWidgetProvider::class.java),
        ).isNotEmpty()
    }
}

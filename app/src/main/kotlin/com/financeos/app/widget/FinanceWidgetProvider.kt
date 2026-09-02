package com.financeos.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.runBlocking

/**
 * FinanceOS 主屏小组件。
 *
 * 数据按“本月已用 / 每日可用 / 本月剩余”展示，口径与 Dashboard 一致。执行时没有 Activity Context，
 * 因此使用 applicationContext 读取数据库；读取与计算放到后台线程并在 goAsync 生命周期内完成。
 */
class FinanceWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.financeos.app.widget.ACTION_REFRESH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, FinanceWidgetProvider::class.java),
            )
            refresh(context, manager, ids)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refresh(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        refresh(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    private fun refresh(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val data = runBlocking { FinanceWidgetUpdater.loadData(appContext) }
                appWidgetIds.forEach { appWidgetId ->
                    val minWidthDp = appWidgetManager
                        .getAppWidgetOptions(appWidgetId)
                        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                    appWidgetManager.updateAppWidget(
                        appWidgetId,
                        FinanceWidgetUpdater.buildRemoteViews(appContext, minWidthDp, data),
                    )
                }
            } catch (error: Exception) {
                val errorViews = FinanceWidgetUpdater.buildRemoteViews(
                    appContext,
                    0,
                    FinanceWidgetData.failed(),
                )
                appWidgetIds.forEach { appWidgetId ->
                    try {
                        appWidgetManager.updateAppWidget(appWidgetId, errorViews)
                    } catch (ignored: Exception) {
                        // 布局更新失败时静默忽略，host 会保留上一版本视图。
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}

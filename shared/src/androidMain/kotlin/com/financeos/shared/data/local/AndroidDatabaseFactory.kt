package com.financeos.shared.data.local

import android.content.Context
import androidx.room3.Room

private const val DATABASE_NAME = "financeos.db"

/** 使用 Android 应用私有目录创建可跨进程重启保留数据的数据库。 */
fun createFinanceOsDatabase(context: Context): FinanceOsDatabase {
    val appContext = context.applicationContext
    val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
    val builder = Room.databaseBuilder<FinanceOsDatabase>(
        context = appContext,
        name = databaseFile.absolutePath,
        factory = { FinanceOsDatabaseConstructor.initialize() },
    )
    return buildFinanceOsDatabase(builder)
}

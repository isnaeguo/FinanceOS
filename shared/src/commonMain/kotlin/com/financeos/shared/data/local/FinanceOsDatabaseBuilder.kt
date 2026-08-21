package com.financeos.shared.data.local

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.financeos.shared.domain.model.DefaultCategories

/** 为各平台的文件路径 Builder 应用一致的数据库配置。 */
fun buildFinanceOsDatabase(
    builder: RoomDatabase.Builder<FinanceOsDatabase>,
): FinanceOsDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .addCallback(DefaultCategoryCallback)
    .build()

private object DefaultCategoryCallback : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        // onCreate 只在数据库文件首次创建后执行，因此默认分类不会在每次启动时重复插入。
        val statement = connection.prepare(
            """
            INSERT OR IGNORE INTO categories (id, name, type, icon_key, is_system)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        )

        try {
            DefaultCategories.all.forEach { category ->
                statement.bindText(1, category.id)
                statement.bindText(2, category.name)
                statement.bindText(3, category.type.name)
                statement.bindText(4, category.iconKey)
                statement.bindLong(5, if (category.isSystem) 1L else 0L)
                statement.step()
                statement.reset()
                statement.clearBindings()
            }
        } finally {
            statement.close()
        }
    }
}

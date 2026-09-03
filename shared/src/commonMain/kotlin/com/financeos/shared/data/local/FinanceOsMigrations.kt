package com.financeos.shared.data.local

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Room schema 版本迁移集合。 */
object FinanceOsMigrations {
    /**
     * v1 → v2：三张表新增同步元数据列。
     *
     * `transactions.updated_at` 用业务时间 `date_time_epoch_millis` 回填——存量流水没有真实修改
     * 时间，用业务时间近似可避免全表并列 `0` 导致合并裁决完全退化；`categories`/`budgets` 的
     * `updated_at` 回填 `0`。`deleted_at` 一律回填 `NULL`，存量数据视为未删除。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE transactions ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL("ALTER TABLE transactions ADD COLUMN deleted_at INTEGER")
            connection.execSQL(
                "UPDATE transactions SET updated_at = date_time_epoch_millis",
            )
            connection.execSQL(
                "ALTER TABLE categories ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL("ALTER TABLE categories ADD COLUMN deleted_at INTEGER")
            connection.execSQL(
                "ALTER TABLE budgets ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL("ALTER TABLE budgets ADD COLUMN deleted_at INTEGER")
        }
    }
}

package com.financeos.shared.data.local

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.financeos.shared.data.local.repository.LocalFinanceDataRepository
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * v1 → v2 真实迁移测试：手工按 v1 schema 建库并写入样例数据，再由 Room 打开触发
 * [FinanceOsMigrations.MIGRATION_1_2]，证明升级后数据无损且回填正确。
 */
class MigrationTest {
    @Test
    fun upgradeFromV1PreservesDataAndBackfillsMetadata() = runTest {
        val directory = Files.createTempDirectory("financeos-migration-test").toFile()
        val databaseFile = directory.resolve("financeos.db")
        try {
            createV1Database(databaseFile.absolutePath)

            val database = buildFinanceOsDatabase(
                Room.databaseBuilder(
                    name = databaseFile.absolutePath,
                    factory = { FinanceOsDatabaseConstructor.initialize() },
                ),
            )
            try {
                // 流水数据无损，updated_at 用业务时间回填，deleted_at 保持未删除。
                val transaction = database.transactionDao().getById("tx-1")
                assertEquals(2_350L, transaction?.amountMinor)
                assertEquals("午饭", transaction?.note)
                assertEquals(1_786_350_600_000L, transaction?.updatedAt)
                assertNull(transaction?.deletedAt)

                // 分类与预算数据无损，updated_at 回填 0。
                val category = database.categoryDao().getAllIncludingDeleted().single()
                assertEquals("system-food", category.id)
                assertEquals(0L, category.updatedAt)
                assertNull(category.deletedAt)

                val budget = database.budgetDao().getAllIncludingDeleted().single()
                assertEquals(300_000L, budget.amountLimitMinor)
                assertEquals(0L, budget.updatedAt)
                assertNull(budget.deletedAt)

                // 快照与合并链路在迁移后的库上可用。
                val snapshot = LocalFinanceDataRepository(database).snapshot()
                assertEquals(listOf("tx-1"), snapshot.transactions.map { it.id })
            } finally {
                database.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** 按导出的 v1 schema 原样建表并写入一行流水、一个分类、一个预算，最后把版本号钉在 1。 */
    private fun createV1Database(path: String) {
        val connection: SQLiteConnection = BundledSQLiteDriver().open(path)
        try {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL, `amount_minor` INTEGER NOT NULL,
                `type` TEXT NOT NULL, `category_id` TEXT NOT NULL, `account_id` TEXT,
                `date_time_epoch_millis` INTEGER NOT NULL, `note` TEXT, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transactions_date_time_epoch_millis` ON `transactions` (`date_time_epoch_millis`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transactions_category_id` ON `transactions` (`category_id`)",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL,
                `type` TEXT NOT NULL, `icon_key` TEXT NOT NULL, `is_system` INTEGER NOT NULL, PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `budgets` (`id` TEXT NOT NULL, `year` INTEGER NOT NULL,
                `month` INTEGER NOT NULL, `category_key` TEXT NOT NULL, `amount_limit_minor` INTEGER NOT NULL,
                PRIMARY KEY(`id`))
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_year_month_category_key` ON `budgets` (`year`, `month`, `category_key`)",
            )
            connection.execSQL(
                """
                INSERT INTO transactions (id, amount_minor, type, category_id, account_id, date_time_epoch_millis, note)
                VALUES ('tx-1', 2350, 'EXPENSE', 'system-food', NULL, 1786350600000, '午饭')
                """.trimIndent(),
            )
            connection.execSQL(
                "INSERT INTO categories (id, name, type, icon_key, is_system) VALUES ('system-food', '餐饮', 'EXPENSE', 'food', 1)",
            )
            connection.execSQL(
                "INSERT INTO budgets (id, year, month, category_key, amount_limit_minor) VALUES ('budget-2026-08', 2026, 8, '', 300000)",
            )
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }
    }
}

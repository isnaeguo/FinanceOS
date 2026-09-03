package com.financeos.shared.data.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.financeos.shared.data.local.dao.BudgetDao
import com.financeos.shared.data.local.dao.CategoryDao
import com.financeos.shared.data.local.dao.TransactionDao
import com.financeos.shared.data.local.entity.BudgetEntity
import com.financeos.shared.data.local.entity.CategoryEntity
import com.financeos.shared.data.local.entity.TransactionEntity

/** FinanceOS 的 Room KMP 数据库入口。 */
@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        BudgetEntity::class,
    ],
    // v2：三张表新增同步元数据列 updated_at / deleted_at，见 FinanceOsMigrations。
    version = 2,
    exportSchema = true,
)
@ConstructedBy(FinanceOsDatabaseConstructor::class)
abstract class FinanceOsDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    abstract fun categoryDao(): CategoryDao

    abstract fun budgetDao(): BudgetDao
}

/** Room 编译器会为各 KMP target 生成 actual 实现。 */
@Suppress("KotlinNoActualForExpect")
expect object FinanceOsDatabaseConstructor : RoomDatabaseConstructor<FinanceOsDatabase> {
    override fun initialize(): FinanceOsDatabase
}

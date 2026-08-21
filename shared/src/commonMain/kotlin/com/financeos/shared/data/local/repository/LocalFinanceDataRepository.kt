package com.financeos.shared.data.local.repository

import androidx.room3.withWriteTransaction
import androidx.room3.withReadTransaction
import com.financeos.shared.data.local.FinanceOsDatabase
import com.financeos.shared.data.local.mapper.toDomain
import com.financeos.shared.data.local.mapper.toEntity
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.FinanceDataImportResult
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.repository.FinanceDataRepository

/** 使用单个 Room 数据库实现完整数据快照、合并导入与原子恢复。 */
class LocalFinanceDataRepository(
    private val database: FinanceOsDatabase,
) : FinanceDataRepository {
    private val transactionDao = database.transactionDao()
    private val categoryDao = database.categoryDao()
    private val budgetDao = database.budgetDao()

    override suspend fun snapshot(): FinanceDataSnapshot = database.withReadTransaction {
        // 三张表在同一读事务中取得，避免导出期间发生写入而形成前后不一致的备份。
        FinanceDataSnapshot(
            transactions = transactionDao.getAll().map { it.toDomain() },
            categories = categoryDao.getAll().map { it.toDomain() },
            budgets = budgetDao.getAll().map { it.toDomain() },
        )
    }

    override suspend fun merge(snapshot: FinanceDataSnapshot): FinanceDataImportResult {
        validateNoDuplicateIds(snapshot)
        val availableCategoryIds = categoryDao.getAll().mapTo(mutableSetOf()) { it.id }
        availableCategoryIds += snapshot.categories.map { it.id }
        validateCategoryReferences(snapshot, availableCategoryIds)

        database.withWriteTransaction {
            if (snapshot.categories.isNotEmpty()) {
                categoryDao.upsertAll(snapshot.categories.map { it.toEntity() })
            }
            if (snapshot.transactions.isNotEmpty()) {
                transactionDao.upsertAll(snapshot.transactions.map { it.toEntity() })
            }
            if (snapshot.budgets.isNotEmpty()) {
                budgetDao.upsertAll(snapshot.budgets.map { it.toEntity() })
            }
        }
        return snapshot.toImportResult()
    }

    override suspend fun replaceAll(snapshot: FinanceDataSnapshot): FinanceDataImportResult {
        validateNoDuplicateIds(snapshot)
        val categories = snapshot.categories.ifEmpty { DefaultCategories.all }
        validateCategoryReferences(snapshot, categories.mapTo(mutableSetOf()) { it.id })

        // 清空和重建必须处于同一写事务，恢复中途失败时 Room 会回滚到原数据。
        database.withWriteTransaction {
            budgetDao.deleteAll()
            transactionDao.deleteAll()
            categoryDao.deleteAll()
            categoryDao.upsertAll(categories.map { it.toEntity() })
            if (snapshot.transactions.isNotEmpty()) {
                transactionDao.upsertAll(snapshot.transactions.map { it.toEntity() })
            }
            if (snapshot.budgets.isNotEmpty()) {
                budgetDao.upsertAll(snapshot.budgets.map { it.toEntity() })
            }
        }
        return snapshot.copy(categories = categories).toImportResult()
    }
}

private fun validateNoDuplicateIds(snapshot: FinanceDataSnapshot) {
    requireUniqueIds(snapshot.transactions.map { it.id }, "流水")
    requireUniqueIds(snapshot.categories.map { it.id }, "分类")
    requireUniqueIds(snapshot.budgets.map { it.id }, "预算")
}

private fun requireUniqueIds(ids: List<String>, label: String) {
    require(ids.size == ids.toSet().size) { "$label 数据包含重复 ID。" }
}

private fun validateCategoryReferences(
    snapshot: FinanceDataSnapshot,
    categoryIds: Set<String>,
) {
    val missingTransactionCategory = snapshot.transactions
        .firstOrNull { it.categoryId !in categoryIds }
        ?.categoryId
    require(missingTransactionCategory == null) {
        "流水引用了不存在的分类：$missingTransactionCategory。"
    }
    val missingBudgetCategory = snapshot.budgets
        .mapNotNull { it.categoryId }
        .firstOrNull { it !in categoryIds }
    require(missingBudgetCategory == null) {
        "预算引用了不存在的分类：$missingBudgetCategory。"
    }
}

private fun FinanceDataSnapshot.toImportResult() = FinanceDataImportResult(
    transactionCount = transactions.size,
    categoryCount = categories.size,
    budgetCount = budgets.size,
)

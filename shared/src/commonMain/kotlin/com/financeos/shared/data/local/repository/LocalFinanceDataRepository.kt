package com.financeos.shared.data.local.repository

import androidx.room3.withWriteTransaction
import androidx.room3.withReadTransaction
import com.financeos.shared.data.local.FinanceOsDatabase
import com.financeos.shared.data.local.mapper.toDomain
import com.financeos.shared.data.local.mapper.toEntity
import com.financeos.shared.data.transfer.canonicalBudgetJson
import com.financeos.shared.data.transfer.canonicalCategoryJson
import com.financeos.shared.data.transfer.canonicalTransactionJson
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.FinanceDataImportResult
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.repository.FinanceDataRepository

/**
 * 使用单个 Room 数据库实现完整数据快照、合并导入与原子恢复。
 *
 * 合并规则只依赖 `(updatedAt, 规范化内容)`，与到达顺序无关（可交换、可收敛）：
 * 1. 本地不存在该 ID → 直接写入；
 * 2. 远端 `updatedAt` 更大 → 远端覆盖本地（远端是墓碑时本地随之转为删除）；
 * 3. 远端 `updatedAt` 更小 → 保留本地；
 * 4. 相等 → 规范化 v2 JSON 单条文档文本字典序大者胜，保证三端对同一 ID 收敛到同一内容。
 *
 * 快照始终包含软删墓碑，删除操作因此能随快照传播；业务读取由各 DAO 查询过滤墓碑。
 */
class LocalFinanceDataRepository(
    private val database: FinanceOsDatabase,
) : FinanceDataRepository {
    private val transactionDao = database.transactionDao()
    private val categoryDao = database.categoryDao()
    private val budgetDao = database.budgetDao()

    /** 导出/同步用完整快照，包含软删墓碑；否则 A 端删除后会被 B 端的旧快照复活。 */
    override suspend fun snapshot(): FinanceDataSnapshot = database.withReadTransaction {
        // 三张表在同一读事务中取得，避免导出期间发生写入而形成前后不一致的备份。
        FinanceDataSnapshot(
            transactions = transactionDao.getAllIncludingDeleted().map { it.toDomain() },
            categories = categoryDao.getAllIncludingDeleted().map { it.toDomain() },
            budgets = budgetDao.getAllIncludingDeleted().map { it.toDomain() },
        )
    }

    /** 按上述规则逐条合并；相同 ID 只让胜者落库，未涉及的数据保持不变。 */
    override suspend fun merge(snapshot: FinanceDataSnapshot): FinanceDataImportResult {
        validateNoDuplicateIds(snapshot)
        // 引用校验的合法分类集合必须包含软删墓碑，否则引用已删分类的流水会被误判非法。
        val availableCategoryIds = categoryDao.getAllIncludingDeleted().mapTo(mutableSetOf()) { it.id }
        availableCategoryIds += snapshot.categories.map { it.id }
        validateCategoryReferences(snapshot, availableCategoryIds)

        database.withWriteTransaction {
            mergeCategories(snapshot.categories)
            mergeTransactions(snapshot.transactions)
            mergeBudgets(snapshot.budgets)
        }
        return snapshot.toImportResult()
    }

    /**
     * 在单个数据库事务中用快照完整替换现有数据，属于备份恢复语义而非合并：
     * 记录的 `updatedAt`/`deletedAt` 随数据带入，不做裁决。
     */
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

    private suspend fun mergeCategories(remote: List<Category>) {
        if (remote.isEmpty()) return
        val localById = categoryDao.getAllIncludingDeleted().associateBy { it.id }
        val winners = remote.mapNotNull { item ->
            val local = localById[item.id]?.toDomain()
            if (local == null ||
                remoteShouldReplace(
                    localUpdatedAt = local.updatedAt,
                    localCanonical = canonicalCategoryJson(local),
                    remoteUpdatedAt = item.updatedAt,
                    remoteCanonical = canonicalCategoryJson(item),
                )
            ) {
                item.toEntity()
            } else {
                null
            }
        }
        if (winners.isNotEmpty()) categoryDao.upsertAll(winners)
    }

    private suspend fun mergeTransactions(remote: List<Transaction>) {
        if (remote.isEmpty()) return
        val localById = transactionDao.getAllIncludingDeleted().associateBy { it.id }
        val winners = remote.mapNotNull { item ->
            val local = localById[item.id]?.toDomain()
            if (local == null ||
                remoteShouldReplace(
                    localUpdatedAt = local.updatedAt,
                    localCanonical = canonicalTransactionJson(local),
                    remoteUpdatedAt = item.updatedAt,
                    remoteCanonical = canonicalTransactionJson(item),
                )
            ) {
                item.toEntity()
            } else {
                null
            }
        }
        if (winners.isNotEmpty()) transactionDao.upsertAll(winners)
    }

    private suspend fun mergeBudgets(remote: List<Budget>) {
        if (remote.isEmpty()) return
        val localById = budgetDao.getAllIncludingDeleted().associateBy { it.id }
        val winners = remote.mapNotNull { item ->
            val local = localById[item.id]?.toDomain()
            if (local == null ||
                remoteShouldReplace(
                    localUpdatedAt = local.updatedAt,
                    localCanonical = canonicalBudgetJson(local),
                    remoteUpdatedAt = item.updatedAt,
                    remoteCanonical = canonicalBudgetJson(item),
                )
            ) {
                item.toEntity()
            } else {
                null
            }
        }
        if (winners.isNotEmpty()) budgetDao.upsertAll(winners)
    }
}

/**
 * 远端是否应覆盖本地。三档裁决全部基于记录自身字段，与记录到达顺序、调用次数无关，
 * 因此任意两端以任意顺序合并同一批快照都会得到相同结果。
 */
private fun remoteShouldReplace(
    localUpdatedAt: Long,
    localCanonical: String,
    remoteUpdatedAt: Long,
    remoteCanonical: String,
): Boolean = when {
    remoteUpdatedAt > localUpdatedAt -> true
    remoteUpdatedAt < localUpdatedAt -> false
    // 规范化文本对本地与远端使用同一套字段顺序和编码，字典序比较在任何端结果一致。
    else -> remoteCanonical > localCanonical
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

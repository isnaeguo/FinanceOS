package com.financeos.shared.data.local.repository

import androidx.room3.withWriteTransaction
import androidx.room3.withReadTransaction
import androidx.sqlite.SQLiteException
import com.financeos.shared.data.local.FinanceOsDatabase
import com.financeos.shared.data.local.dao.PeriodExpenseTotals
import com.financeos.shared.data.local.mapper.categoryKey
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
import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.MonthlyBudgetOverview
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.repository.FinanceDataRepository
import com.financeos.shared.domain.usecase.dailyBudgetFromExpenses
import com.financeos.shared.domain.usecase.remainingDaysAt
import kotlin.time.Instant

/**
 * 使用单个 Room 数据库实现完整数据快照、合并导入与原子恢复。
 *
 * 合并规则只依赖 `(updatedAt, 规范化内容)`，与到达顺序无关（可交换、可收敛）：
 * 1. 本地不存在该 ID → 直接写入；
 * 2. 远端 `updatedAt` 更大 → 远端覆盖本地（远端是墓碑时本地随之转为删除）；
 * 3. 远端 `updatedAt` 更小 → 保留本地；
 * 4. 相等 → 优先保留墓碑，再以规范化 v2 JSON 文本打破平局。
 *
 * 预算以月份与分类范围为合并键，兼容不同设备给同一预算生成不同业务 ID。
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
    @Throws(Exception::class)
    override suspend fun snapshot(): FinanceDataSnapshot = database.withReadTransaction {
        // 三张表在同一读事务中取得，避免导出期间发生写入而形成前后不一致的备份。
        FinanceDataSnapshot(
            transactions = transactionDao.getAllIncludingDeleted().map { it.toDomain() },
            categories = categoryDao.getAllIncludingDeleted().map { it.toDomain() },
            budgets = budgetDao.getAllIncludingDeleted().map { it.toDomain() },
        )
    }

    /** Android 和 Apple 小组件仅在同一个读事务中读取当前月的数据。 */
    @Throws(Exception::class)
    suspend fun monthlyBudgetOverview(
        period: MonthPeriod,
        dayOfMonth: Int,
        startOfToday: Instant,
    ): MonthlyBudgetOverview = database.withReadTransaction {
        val remainingDays = period.remainingDaysAt(dayOfMonth, startOfToday)
        val totals = readExpenseTotals(
            startEpochMillis = period.startInclusive.storageBoundary(),
            endEpochMillis = period.endExclusive.storageBoundary(),
            todayEpochMillis = startOfToday.storageBoundary(),
        )
        val limit = budgetDao.get(period.month.year, period.month.month, categoryKey(null))?.amountLimitMinor
        MonthlyBudgetOverview(
            amountUsed = totals.spent,
            amountRemaining = limit?.minus(totals.spent),
            dailyAvailable = limit?.let { dailyBudgetFromExpenses(it, totals.spentBeforeToday, remainingDays).dailyAmount },
        )
    }

    private suspend fun readExpenseTotals(
        startEpochMillis: Long,
        endEpochMillis: Long,
        todayEpochMillis: Long,
    ): PeriodExpenseTotals = try {
        transactionDao.expenseTotals(startEpochMillis, endEpochMillis, todayEpochMillis)
    } catch (error: SQLiteException) {
        if (error.message?.contains("integer overflow") != true) throw error

        // 极端金额仍以整数计算，避免 SQL 转浮点后丢失精度。仅在 SUM 溢出时读取金额列。
        var spent = 0L
        var spentBeforeToday = 0L
        for (row in transactionDao.getAmountsByPeriod(startEpochMillis, endEpochMillis)) {
            if (row.type != TransactionType.EXPENSE.name) continue
            spent += minOf(row.amount, Long.MAX_VALUE - spent)
            if (row.epochMillis < todayEpochMillis) {
                spentBeforeToday += minOf(row.amount, Long.MAX_VALUE - spentBeforeToday)
            }
        }
        PeriodExpenseTotals(spent, spentBeforeToday)
    }

    /** 按上述规则逐条合并；相同 ID 只让胜者落库，未涉及的数据保持不变。 */
    @Throws(Exception::class)
    override suspend fun merge(snapshot: FinanceDataSnapshot): FinanceDataImportResult {
        validateNoDuplicateIds(snapshot)
        database.withWriteTransaction {
            if (snapshot.transactions.isNotEmpty() || snapshot.budgets.isNotEmpty()) {
                // 引用允许指向墓碑，历史流水仍需保留已删除分类的 ID。
                val categoryIds = categoryDao.getAllIncludingDeleted().mapTo(mutableSetOf()) { it.id }
                snapshot.categories.mapTo(categoryIds) { it.id }
                validateCategoryReferences(snapshot, categoryIds)
            }
            mergeCategories(snapshot.categories)
            mergeTransactions(snapshot.transactions)
            mergeBudgets(snapshot.budgets)
        }
        return snapshot.toImportResult()
    }

    /** 外部账单仅写入新 ID，保留已有流水的人工修改与删除状态。 */
    @Throws(Exception::class)
    override suspend fun importTransactions(transactions: List<Transaction>): FinanceDataImportResult {
        if (transactions.isEmpty()) return FinanceDataImportResult(0, 0, 0)
        require(transactions.none { it.deletedAt != null }) { "外部账单不能包含已删除的流水。" }
        val inserted = database.withWriteTransaction {
            val categoryIds = categoryDao.getAll().mapTo(mutableSetOf()) { it.id }
            transactions.chunked(SQLITE_ID_BATCH_SIZE).sumOf { batch ->
                val existingIds = transactionDao.getExistingIds(batch.map { it.id }).toHashSet()
                val newTransactions = batch.filter { it.id !in existingIds }
                require(newTransactions.all { it.categoryId in categoryIds }) {
                    "待导入的流水引用了不可用的分类，请重新识别账单。"
                }
                transactionDao.insertNew(newTransactions.map { it.toEntity() }).count { it != -1L }
            }
        }
        return FinanceDataImportResult(transactionCount = inserted, categoryCount = 0, budgetCount = 0)
    }

    /**
     * 在单个数据库事务中用快照完整替换现有数据，属于备份恢复语义而非合并：
     * 记录的 `updatedAt`/`deletedAt` 随数据带入，不做裁决。
     */
    @Throws(Exception::class)
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
            if (local == null || remoteShouldReplace(
                    local.updatedAt, local.deletedAt, item.updatedAt, item.deletedAt,
                ) { canonicalCategoryJson(item) > canonicalCategoryJson(local) }
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
        remote.chunked(SQLITE_ID_BATCH_SIZE).forEach { batch ->
            val localById = transactionDao.getByIdsIncludingDeleted(batch.map { it.id }).associateBy { it.id }
            val winners = batch.mapNotNull { item ->
                val local = localById[item.id]?.toDomain()
                if (local == null || remoteShouldReplace(
                        local.updatedAt, local.deletedAt, item.updatedAt, item.deletedAt,
                    ) { item != local && canonicalTransactionJson(item) > canonicalTransactionJson(local) }
                ) {
                    item.toEntity()
                } else {
                    null
                }
            }
            if (winners.isNotEmpty()) transactionDao.upsertAll(winners)
        }
    }

    private suspend fun mergeBudgets(remote: List<Budget>) {
        if (remote.isEmpty()) return
        val local = budgetDao.getAllIncludingDeleted().map { it.toDomain() }
        val localById = local.associateBy { it.id }
        val localByScope = local.associateBy { it.scope }
        val winners = remote.mapNotNull { item ->
            val existingById = localById[item.id]
            require(existingById == null || existingById.scope == item.scope) {
                "预算 ID 对应的月份或分类与本机记录冲突。"
            }
            val existing = localByScope[item.scope]
            if (existing == null || remoteShouldReplace(
                    existing.updatedAt, existing.deletedAt, item.updatedAt, item.deletedAt,
                ) { canonicalBudgetJson(item) > canonicalBudgetJson(existing) }
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
private inline fun remoteShouldReplace(
    localUpdatedAt: Long,
    localDeletedAt: Long?,
    remoteUpdatedAt: Long,
    remoteDeletedAt: Long?,
    winsCanonicalTie: () -> Boolean,
): Boolean = when {
    remoteUpdatedAt > localUpdatedAt -> true
    remoteUpdatedAt < localUpdatedAt -> false
    (remoteDeletedAt != null) != (localDeletedAt != null) -> remoteDeletedAt != null
    else -> winsCanonicalTie()
}

private fun validateNoDuplicateIds(snapshot: FinanceDataSnapshot) {
    requireUniqueIds(snapshot.transactions.map { it.id }, "流水")
    requireUniqueIds(snapshot.categories.map { it.id }, "分类")
    requireUniqueIds(snapshot.budgets.map { it.id }, "预算")
    require(snapshot.budgets.distinctBy { it.scope }.size == snapshot.budgets.size) {
        "同一月份和分类不能包含多条预算。"
    }
}

private val Budget.scope get() = month to categoryId

private const val SQLITE_ID_BATCH_SIZE = 500

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

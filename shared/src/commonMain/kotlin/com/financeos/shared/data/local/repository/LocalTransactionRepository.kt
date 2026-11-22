package com.financeos.shared.data.local.repository

import com.financeos.shared.data.local.dao.PeriodAmountRow
import com.financeos.shared.data.local.dao.TransactionDao
import com.financeos.shared.data.local.mapper.toDomain
import com.financeos.shared.data.local.mapper.toEntity
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionAmount
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.repository.TransactionRepository
import com.financeos.shared.domain.time.EpochClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlin.time.Instant

/** 使用 Room DAO 实现流水业务存取。 */
class LocalTransactionRepository(
    private val dao: TransactionDao,
    private val clock: EpochClock = EpochClock.system,
) : TransactionRepository {
    /**
     * 同一 ID 的新增和编辑均原子写入。编辑时修改时间严格晚于旧版本（除非已到存储上限）。
     */
    @Throws(Exception::class)
    override suspend fun add(transaction: Transaction) {
        val now = clock.nowMillis()
        require(transaction.deletedAt == null || transaction.deletedAt <= now) {
            "Transaction deletedAt must not be in the future."
        }
        dao.saveLocal(transaction.copy(updatedAt = now).toEntity())
    }

    /**
     * 软删流水：写入 `deleted_at = now` 的墓碑并同步刷新 `updated_at`，记录本身保留，
     * 使删除能随快照传播到其他设备；返回 `true` 表示该流水此前存在且未删除。
     */
    @Throws(Exception::class)
    override suspend fun delete(id: String): Boolean {
        val now = clock.nowMillis()
        return dao.softDeleteById(id, deletedAt = now, updatedAt = now) > 0
    }

    /** 应用已预览的分类建议。流水变化或分类不可用时返回 false。 */
    @Throws(Exception::class)
    suspend fun updateCategory(expected: Transaction, newCategoryId: String): Boolean {
        if (newCategoryId.isBlank() || newCategoryId == expected.categoryId) return false
        return update(expected, expected.copy(categoryId = newCategoryId))
    }

    /** 仅当原流水仍匹配时，创建并分配与该流水收支方向一致的分类。 */
    @Throws(Exception::class)
    suspend fun updateCategoryWithNewCategory(
        expected: Transaction,
        categoryId: String,
        name: String,
    ): Boolean {
        if (expected.deletedAt != null) return false
        val label = name.trim()
        require(label.isNotEmpty()) { "请输入分类名称。" }
        require(label.length <= 40 && label.none(Char::isISOControl)) {
            "分类名称最多 40 个字符，且不能包含控制字符。"
        }
        require(customCategoryIdPattern.matches(categoryId)) { "分类标识无效，请重试。" }
        val category = Category(
            id = categoryId,
            name = label,
            type = CategoryType.valueOf(expected.type.name),
            iconKey = "other",
            isSystem = false,
            updatedAt = clock.nowMillis(),
        )
        return dao.assignNewCategory(expected.toEntity(), category.toEntity())
    }

    /** 编辑期间发生过同步、删除或其他修改时保留数据库中的当前版本。 */
    @Throws(Exception::class)
    suspend fun update(expected: Transaction, replacement: Transaction): Boolean {
        if (expected.id != replacement.id || expected.deletedAt != null || replacement.deletedAt != null) {
            return false
        }
        return dao.updateLocal(
            expected = expected.toEntity(),
            replacement = replacement.copy(updatedAt = clock.nowMillis()).toEntity(),
        )
    }

    override suspend fun get(id: String): Transaction? = dao.getById(id)?.toDomain()

    override suspend fun getAll(): List<Transaction> = dao.getAll().map { it.toDomain() }

    override fun observeAll(): Flow<List<Transaction>> = dao.observeAll()
        .map { entities -> entities.map { it.toDomain() } }
        .flowOn(Dispatchers.Default)

    override suspend fun getByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Transaction> {
        require(startInclusive < endExclusive) {
            "Transaction period must have a positive duration."
        }
        return dao.getByPeriod(
            startEpochMillis = startInclusive.storageBoundary(),
            endEpochMillis = endExclusive.storageBoundary(),
        ).map { it.toDomain() }
    }

    override fun observeByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> {
        require(startInclusive < endExclusive) {
            "Transaction period must have a positive duration."
        }
        return dao.observeByPeriod(
            startEpochMillis = startInclusive.storageBoundary(),
            endEpochMillis = endExclusive.storageBoundary(),
        )
            .map { entities -> entities.map { it.toDomain() } }
            // 大批量 Entity 映射不应占用 Android 主线程，避免列表或首页更新时阻塞绘制。
            .flowOn(Dispatchers.Default)
    }

    override suspend fun getAmountsByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<TransactionAmount> {
        require(startInclusive < endExclusive) { "Transaction period must have a positive duration." }
        return dao.getAmountsByPeriod(startInclusive.storageBoundary(), endExclusive.storageBoundary())
            .map(PeriodAmountRow::toDomainAmount)
    }

    override fun observeAmountsByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<TransactionAmount>> {
        require(startInclusive < endExclusive) { "Transaction period must have a positive duration." }
        return dao.observeAmountsByPeriod(startInclusive.storageBoundary(), endExclusive.storageBoundary())
            .map { rows -> rows.map(PeriodAmountRow::toDomainAmount) }
            .flowOn(Dispatchers.Default)
    }

    override suspend fun getByMonth(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Transaction> {
        require(startInclusive < endExclusive) {
            "Transaction month range must have a positive duration."
        }
        return getByPeriod(startInclusive, endExclusive)
    }

    override fun observeByMonth(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> {
        require(startInclusive < endExclusive) {
            "Transaction month range must have a positive duration."
        }
        return observeByPeriod(startInclusive, endExclusive)
    }
}

private val customCategoryIdPattern = Regex("user-[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")

/** 存储精度为毫秒。区间端点向上取整，保证半开区间在毫秒记录上的比较不丢边界。 */
internal fun Instant.storageBoundary(): Long {
    val millis = toEpochMilliseconds()
    return if (millis < Long.MAX_VALUE && Instant.fromEpochMilliseconds(millis) < this) millis + 1 else millis
}

private fun PeriodAmountRow.toDomainAmount() = TransactionAmount(
    amount = amount,
    type = TransactionType.valueOf(type),
    dateTime = Instant.fromEpochMilliseconds(epochMillis),
)

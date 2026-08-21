package com.financeos.shared.data.local.mapper

import com.financeos.shared.data.local.entity.BudgetEntity
import com.financeos.shared.data.local.entity.CategoryEntity
import com.financeos.shared.data.local.entity.TransactionEntity
import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlin.time.Instant

private const val TOTAL_BUDGET_CATEGORY_KEY = ""

internal fun Transaction.toEntity() = TransactionEntity(
    id = id,
    // 金额原样保存为最小货币单位的 Long，转换过程不得改变单位或精度。
    amountMinor = amount,
    type = type.name,
    categoryId = categoryId,
    accountId = accountId,
    // Instant 统一转换为 Unix 毫秒，避免数据库依赖平台日期类型。
    dateTimeEpochMillis = dateTime.toEpochMilliseconds(),
    note = note,
)

internal fun TransactionEntity.toDomain() = Transaction(
    id = id,
    amount = amountMinor,
    type = TransactionType.valueOf(type),
    categoryId = categoryId,
    accountId = accountId,
    // 从同一 Unix 毫秒值恢复 Instant，保证跨平台时间语义一致。
    dateTime = Instant.fromEpochMilliseconds(dateTimeEpochMillis),
    note = note,
)

internal fun CategoryEntity.toDomain() = Category(
    id = id,
    name = name,
    type = CategoryType.valueOf(type),
    iconKey = iconKey,
    isSystem = isSystem,
)

internal fun Category.toEntity() = CategoryEntity(
    id = id,
    name = name,
    type = type.name,
    // iconKey 是跨平台语义键，数据库不保存 Android Drawable ID。
    iconKey = iconKey,
    isSystem = isSystem,
)

internal fun Budget.toEntity() = BudgetEntity(
    id = id,
    year = month.year,
    month = month.month,
    // SQLite 的唯一索引允许多个 NULL，因此使用保留空串确保每月只有一个总预算。
    categoryKey = categoryId ?: TOTAL_BUDGET_CATEGORY_KEY,
    // 与 Transaction 相同，预算金额始终以最小货币单位原样持久化。
    amountLimitMinor = amountLimit,
)

internal fun BudgetEntity.toDomain() = Budget(
    id = id,
    month = BudgetMonth(year = year, month = month),
    amountLimit = amountLimitMinor,
    // 空串只属于数据库存储约定，Domain 仍使用 null 表达月总预算。
    categoryId = categoryKey.ifEmpty { null },
)

internal fun categoryKey(categoryId: String?): String = categoryId ?: TOTAL_BUDGET_CATEGORY_KEY

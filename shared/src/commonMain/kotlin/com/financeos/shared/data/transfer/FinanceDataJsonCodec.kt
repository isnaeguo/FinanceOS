package com.financeos.shared.data.transfer

import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.FinanceDataSnapshot
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.Throws

/** FinanceOS 版本化 JSON 快照编解码器。 */
class FinanceDataJsonCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        // 删除墓碑的可选字段在值为 null 时省略，保持备份文档紧凑。
        explicitNulls = false
    }

    /** 输出稳定、可读且不含平台存储细节的 JSON，总是包含软删墓碑以传播删除操作。 */
    fun encode(snapshot: FinanceDataSnapshot): String = json.encodeToString(
        BackupDocument(
            transactions = snapshot.transactions
                .sortedBy(Transaction::id)
                .map(TransactionDocument::fromDomain),
            categories = snapshot.categories
                .sortedBy(Category::id)
                .map(CategoryDocument::fromDomain),
            budgets = snapshot.budgets
                .sortedBy(Budget::id)
                .map(BudgetDocument::fromDomain),
        ),
    )

    /**
     * 接受 schema_version 1 与 2 两种读入口，写出固定为 2。
     *
     * v1 文档不携带同步元数据，读取时统一映射为 `updatedAt = 0`、`deletedAt = null`：
     * v1 记录在合并中视为最老，会被任何 v2 记录覆盖。这是有意取舍——v1 格式没有修改时间，
     * 无从判断新旧，只有让它稳定地输掉裁决才能保证收敛。
     */
    @Throws(DataTransferException::class)
    fun decode(content: String): FinanceDataSnapshot {
        try {
            val document = json.decodeFromString<BackupDocument>(content.removePrefix("\uFEFF"))
            require(document.format == BACKUP_FORMAT) { "不是 FinanceOS 数据文件。" }
            require(document.schemaVersion in SUPPORTED_SCHEMA_VERSIONS) {
                "暂不支持此备份版本：${document.schemaVersion}。"
            }
            return FinanceDataSnapshot(
                transactions = document.transactions.map(TransactionDocument::toDomain),
                categories = document.categories.map(CategoryDocument::toDomain),
                budgets = document.budgets.map(BudgetDocument::toDomain),
            )
        } catch (error: DataTransferException) {
            throw error
        } catch (error: Exception) {
            throw DataTransferException(error.message ?: "JSON 数据格式不正确。", error)
        }
    }
}

/** 导入文件无法被安全解释时抛出的可展示错误。 */
class DataTransferException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

@Serializable
private data class BackupDocument(
    val format: String = BACKUP_FORMAT,
    @SerialName("schema_version")
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val transactions: List<TransactionDocument> = emptyList(),
    val categories: List<CategoryDocument> = emptyList(),
    val budgets: List<BudgetDocument> = emptyList(),
)

@Serializable
private data class TransactionDocument(
    val id: String,
    @SerialName("amount_minor")
    val amountMinor: Long,
    val type: String,
    @SerialName("category_id")
    val categoryId: String,
    @SerialName("account_id")
    val accountId: String? = null,
    @SerialName("date_time_epoch_millis")
    val dateTimeEpochMillis: Long,
    val note: String? = null,
    @SerialName("updated_at_epoch_millis")
    val updatedAtEpochMillis: Long = 0,
    @SerialName("deleted_at_epoch_millis")
    val deletedAtEpochMillis: Long? = null,
) {
    fun toDomain() = Transaction(
        id = id,
        amount = amountMinor,
        type = enumValueOrTransferError(type, "流水类型"),
        categoryId = categoryId,
        accountId = accountId,
        // 备份使用 Unix 毫秒，跨平台恢复时不会受到设备时区影响。
        dateTime = Instant.fromEpochMilliseconds(dateTimeEpochMillis),
        note = note,
        updatedAt = updatedAtEpochMillis,
        deletedAt = deletedAtEpochMillis,
    )

    companion object {
        fun fromDomain(transaction: Transaction) = TransactionDocument(
            id = transaction.id,
            amountMinor = transaction.amount,
            type = transaction.type.name,
            categoryId = transaction.categoryId,
            accountId = transaction.accountId,
            dateTimeEpochMillis = transaction.dateTime.toEpochMilliseconds(),
            note = transaction.note,
            updatedAtEpochMillis = transaction.updatedAt,
            deletedAtEpochMillis = transaction.deletedAt,
        )
    }
}

@Serializable
private data class CategoryDocument(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("icon_key")
    val iconKey: String,
    @SerialName("is_system")
    val isSystem: Boolean,
    @SerialName("updated_at_epoch_millis")
    val updatedAtEpochMillis: Long = 0,
    @SerialName("deleted_at_epoch_millis")
    val deletedAtEpochMillis: Long? = null,
) {
    fun toDomain() = Category(
        id = id,
        name = name,
        type = enumValueOrTransferError(type, "分类类型"),
        iconKey = iconKey,
        isSystem = isSystem,
        updatedAt = updatedAtEpochMillis,
        deletedAt = deletedAtEpochMillis,
    )

    companion object {
        fun fromDomain(category: Category) = CategoryDocument(
            id = category.id,
            name = category.name,
            type = category.type.name,
            iconKey = category.iconKey,
            isSystem = category.isSystem,
            updatedAtEpochMillis = category.updatedAt,
            deletedAtEpochMillis = category.deletedAt,
        )
    }
}

@Serializable
private data class BudgetDocument(
    val id: String,
    val year: Int,
    val month: Int,
    @SerialName("category_id")
    val categoryId: String? = null,
    @SerialName("amount_limit_minor")
    val amountLimitMinor: Long,
    @SerialName("updated_at_epoch_millis")
    val updatedAtEpochMillis: Long = 0,
    @SerialName("deleted_at_epoch_millis")
    val deletedAtEpochMillis: Long? = null,
) {
    fun toDomain() = Budget(
        id = id,
        month = BudgetMonth(year = year, month = month),
        categoryId = categoryId,
        amountLimit = amountLimitMinor,
        updatedAt = updatedAtEpochMillis,
        deletedAt = deletedAtEpochMillis,
    )

    companion object {
        fun fromDomain(budget: Budget) = BudgetDocument(
            id = budget.id,
            year = budget.month.year,
            month = budget.month.month,
            categoryId = budget.categoryId,
            amountLimitMinor = budget.amountLimit,
            updatedAtEpochMillis = budget.updatedAt,
            deletedAtEpochMillis = budget.deletedAt,
        )
    }
}

private inline fun <reified T : Enum<T>> enumValueOrTransferError(
    value: String,
    fieldName: String,
): T = try {
    enumValueOf<T>(value)
} catch (error: IllegalArgumentException) {
    throw DataTransferException("$fieldName 无效：$value。", error)
}

/**
 * 单条记录的规范化 v2 JSON 文本（紧凑、字段顺序固定、`null` 省略）。
 *
 * 合并在 `updatedAt` 相等时以规范化文本的字典序大者胜；同一记录在任何端序列化结果必须
 * 逐字节一致，这是三端确定性收敛的基础。
 */
internal fun canonicalTransactionJson(transaction: Transaction): String =
    canonicalJson.encodeToString(TransactionDocument.fromDomain(transaction))

internal fun canonicalCategoryJson(category: Category): String =
    canonicalJson.encodeToString(CategoryDocument.fromDomain(category))

internal fun canonicalBudgetJson(budget: Budget): String =
    canonicalJson.encodeToString(BudgetDocument.fromDomain(budget))

private val canonicalJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

private const val BACKUP_FORMAT = "financeos-backup"
private const val CURRENT_SCHEMA_VERSION = 2
private val SUPPORTED_SCHEMA_VERSIONS = setOf(1, CURRENT_SCHEMA_VERSION)

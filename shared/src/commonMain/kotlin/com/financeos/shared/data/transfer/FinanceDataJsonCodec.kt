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

/** FinanceOS 版本化 JSON 快照编解码器。 */
class FinanceDataJsonCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 输出稳定、可读且不含平台存储细节的 JSON。 */
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

    /** 只接受当前支持的 FinanceOS 备份版本，避免错误解释未来格式。 */
    fun decode(content: String): FinanceDataSnapshot {
        try {
            val document = json.decodeFromString<BackupDocument>(content.removePrefix("\uFEFF"))
            require(document.format == BACKUP_FORMAT) { "不是 FinanceOS 数据文件。" }
            require(document.schemaVersion == CURRENT_SCHEMA_VERSION) {
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
) {
    fun toDomain() = Category(
        id = id,
        name = name,
        type = enumValueOrTransferError(type, "分类类型"),
        iconKey = iconKey,
        isSystem = isSystem,
    )

    companion object {
        fun fromDomain(category: Category) = CategoryDocument(
            id = category.id,
            name = category.name,
            type = category.type.name,
            iconKey = category.iconKey,
            isSystem = category.isSystem,
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
) {
    fun toDomain() = Budget(
        id = id,
        month = BudgetMonth(year = year, month = month),
        categoryId = categoryId,
        amountLimit = amountLimitMinor,
    )

    companion object {
        fun fromDomain(budget: Budget) = BudgetDocument(
            id = budget.id,
            year = budget.month.year,
            month = budget.month.month,
            categoryId = budget.categoryId,
            amountLimitMinor = budget.amountLimit,
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

private const val BACKUP_FORMAT = "financeos-backup"
private const val CURRENT_SCHEMA_VERSION = 1

import Foundation
import FinanceOSShared

// KMP 领域对象 ↔ 显示投影转换。墓碑记录（deletedAt != nil）在这里被保留，供导出快照使用；
// UI 消费的可见列表由 FinanceStoreAdapter 统一过滤。

extension Transaction {
    init(kmp: KotlinTransaction) {
        id = kmp.id
        amount = kmp.amount
        type = kmp.type == .income ? .income : .expense
        categoryId = kmp.categoryId
        accountId = kmp.accountId
        dateTime = KMPBridge.date(from: kmp.dateTime)
        note = kmp.note
        updatedAt = kmp.updatedAt
        deletedAt = kmp.deletedAt?.int64Value
    }

    var isDeleted: Bool { deletedAt != nil }
}

extension Category {
    init(kmp: KotlinCategory) {
        id = kmp.id
        name = kmp.name
        type = kmp.type == .common ? .common : (kmp.type == .income ? .income : .expense)
        iconKey = kmp.iconKey
        isSystem = kmp.isSystem
        updatedAt = kmp.updatedAt
        deletedAt = kmp.deletedAt?.int64Value
    }
}

extension Budget {
    init(kmp: KotlinBudget) {
        id = kmp.id
        month = BudgetMonth(year: Int(kmp.month.year), month: Int(kmp.month.month))
        amountLimit = kmp.amountLimit
        categoryId = kmp.categoryId
        updatedAt = kmp.updatedAt
        deletedAt = kmp.deletedAt?.int64Value
    }
}

// KMP → 显示投影快照 / 结果

extension FinanceDataSnapshot {
    init(kmp: KotlinFinanceDataSnapshot) {
        transactions = kmp.transactions.map(Transaction.init(kmp:))
        categories = kmp.categories.map(Category.init(kmp:))
        budgets = kmp.budgets.map(Budget.init(kmp:))
    }
}

extension FinanceDataImportResult {
    init(kmp: KotlinFinanceDataImportResult) {
        transactionCount = Int(kmp.transactionCount)
        categoryCount = Int(kmp.categoryCount)
        budgetCount = Int(kmp.budgetCount)
    }
}

// 显示投影 → KMP

extension Transaction {
    func toKMP() -> KotlinTransaction {
        KotlinTransaction(
            id: id,
            amount: amount,
            type: type == .income ? .income : .expense,
            categoryId: categoryId,
            accountId: accountId,
            dateTime: KMPBridge.instant(from: dateTime),
            note: note,
            updatedAt: updatedAt,
            deletedAt: deletedAt.map { KotlinLong(value: $0) }
        )
    }
}

extension Category {
    func toKMP() -> KotlinCategory {
        KotlinCategory(
            id: id,
            name: name,
            type: type == .common ? .common : (type == .income ? .income : .expense),
            iconKey: iconKey,
            isSystem: isSystem,
            updatedAt: updatedAt,
            deletedAt: deletedAt.map { KotlinLong(value: $0) }
        )
    }
}

extension Budget {
    func toKMP() -> KotlinBudget {
        KotlinBudget(
            id: id,
            month: KotlinBudgetMonth(year: Int32(month.year), month: Int32(month.month)),
            amountLimit: amountLimit,
            categoryId: categoryId,
            updatedAt: updatedAt,
            deletedAt: deletedAt.map { KotlinLong(value: $0) }
        )
    }
}

extension FinanceDataSnapshot {
    func toKMP() -> KotlinFinanceDataSnapshot {
        KotlinFinanceDataSnapshot(
            transactions: transactions.map { $0.toKMP() },
            categories: categories.map { $0.toKMP() },
            budgets: budgets.map { $0.toKMP() }
        )
    }
}

// 类型别名：KMP 导出的 Swift 名（去掉与显示层冲突的歧义场景时使用 Kotlin* 前缀阅读）
typealias KotlinTransaction = FinanceOSShared.Transaction
typealias KotlinCategory = FinanceOSShared.Category
typealias KotlinBudget = FinanceOSShared.Budget
typealias KotlinBudgetMonth = FinanceOSShared.BudgetMonth
typealias KotlinFinanceDataSnapshot = FinanceOSShared.FinanceDataSnapshot
typealias KotlinFinanceDataImportResult = FinanceOSShared.FinanceDataImportResult
typealias KotlinEpochClock = FinanceOSShared.EpochClock
typealias KotlinJsonCodec = FinanceOSShared.FinanceDataJsonCodec
typealias KotlinTransactionCsvCodec = FinanceOSShared.TransactionCsvCodec
typealias KotlinImportResult = FinanceOSShared.TableTransactionImporter.DecodeResult

/// KMP 数据流转错误统一映射为可展示错误。
enum KMPError {
    static func wrap(_ error: Error) -> DataTransferError {
        DataTransferError(message: error.localizedDescription)
    }
}

import Foundation

// MARK: - 流水

/** 流水的资金方向。 */
public enum TransactionType: String, Codable, Sendable, CaseIterable {
    case income = "INCOME"
    case expense = "EXPENSE"

    public var label: String {
        switch self {
        case .income: "收入"
        case .expense: "支出"
        }
    }
}

/// 一笔收入或支出流水。
///
/// 金额始终保存为正的最小货币单位，收支方向仅由 `type` 决定，避免负金额与类型组合产生歧义。
public struct Transaction: Identifiable, Hashable, Sendable, Codable {
    public var id: String
    /// 用户货币的最小单位金额，例如人民币的“分”。
    public var amount: Int64
    public var type: TransactionType
    public var categoryId: String
    public var accountId: String?
    public var dateTime: Date
    public var note: String?

    public init(
        id: String,
        amount: Int64,
        type: TransactionType,
        categoryId: String,
        accountId: String? = nil,
        dateTime: Date,
        note: String? = nil
    ) {
        precondition(!id.trimmingCharacters(in: .whitespaces).isEmpty, "Transaction id must not be blank.")
        precondition(amount > 0, "Transaction amount must be greater than zero.")
        precondition(!categoryId.trimmingCharacters(in: .whitespaces).isEmpty, "Transaction categoryId must not be blank.")
        self.id = id
        self.amount = amount
        self.type = type
        self.categoryId = categoryId
        self.accountId = accountId
        self.dateTime = dateTime
        self.note = note
    }
}

// MARK: - 分类

/** 分类允许关联的流水方向。 */
public enum CategoryType: String, Codable, Sendable, CaseIterable {
    case income = "INCOME"
    case expense = "EXPENSE"
    case common = "COMMON"

    public var label: String {
        switch self {
        case .income: "收入"
        case .expense: "支出"
        case .common: "通用"
        }
    }
}

/// 用于组织流水的业务分类。
///
/// `iconKey` 是跨平台语义键，各端负责将它映射为平台原生图标。
public struct Category: Identifiable, Hashable, Sendable, Codable {
    public var id: String
    public var name: String
    public var type: CategoryType
    public var iconKey: String
    public var isSystem: Bool

    public init(id: String, name: String, type: CategoryType, iconKey: String, isSystem: Bool = false) {
        precondition(!id.trimmingCharacters(in: .whitespaces).isEmpty, "Category id must not be blank.")
        precondition(!name.trimmingCharacters(in: .whitespaces).isEmpty, "Category name must not be blank.")
        precondition(!iconKey.trimmingCharacters(in: .whitespaces).isEmpty, "Category iconKey must not be blank.")
        self.id = id
        self.name = name
        self.type = type
        self.iconKey = iconKey
        self.isSystem = isSystem
    }

    /// 该分类是否可用于指定方向的流水。
    public func accepts(_ type: TransactionType) -> Bool {
        switch self.type {
        case .income: type == .income
        case .expense: type == .expense
        case .common: true
        }
    }
}

/// FinanceOS 内置分类，固定 ID 与 Android 端保持一致。
public enum DefaultCategories {
    public static let all: [Category] = [
        Category(id: "system-food", name: "餐饮", type: .expense, iconKey: "food", isSystem: true),
        Category(id: "system-transport", name: "交通", type: .expense, iconKey: "transport", isSystem: true),
        Category(id: "system-shopping", name: "购物", type: .expense, iconKey: "shopping", isSystem: true),
        Category(id: "system-entertainment", name: "娱乐", type: .expense, iconKey: "entertainment", isSystem: true),
        Category(id: "system-digital", name: "数码", type: .expense, iconKey: "digital", isSystem: true),
        Category(id: "system-learning", name: "学习", type: .expense, iconKey: "learning", isSystem: true),
        Category(id: "system-travel", name: "旅行", type: .expense, iconKey: "travel", isSystem: true),
        Category(id: "system-daily-needs", name: "日用品", type: .expense, iconKey: "daily-needs", isSystem: true),
        Category(id: "system-income", name: "工资/生活费", type: .income, iconKey: "income", isSystem: true),
        Category(id: "system-other", name: "其他", type: .common, iconKey: "other", isSystem: true),
    ]
}

// MARK: - 预算月份

/// 跨平台的预算月份。
public struct BudgetMonth: Hashable, Comparable, Sendable, Codable {
    public var year: Int
    /// 1...12
    public var month: Int

    public init(year: Int, month: Int) {
        precondition(year > 0, "Budget year must be greater than zero.")
        precondition((1...12).contains(month), "Budget month must be between 1 and 12.")
        self.year = year
        self.month = month
    }

    public static func < (lhs: BudgetMonth, rhs: BudgetMonth) -> Bool {
        (lhs.year, lhs.month) < (rhs.year, rhs.month)
    }

    public var daysInMonth: Int {
        switch month {
        case 2: isLeapYear ? 29 : 28
        case 4, 6, 9, 11: 30
        default: 31
        }
    }

    public var isLeapYear: Bool {
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    }

    public func next() -> BudgetMonth {
        month == 12 ? BudgetMonth(year: year + 1, month: 1) : BudgetMonth(year: year, month: month + 1)
    }

    public func previous() -> BudgetMonth {
        month == 1 ? BudgetMonth(year: year - 1, month: 12) : BudgetMonth(year: year, month: month - 1)
    }

    /// 用户时区中的一个月份及其对应的半开时间边界，Domain 只使用半开区间。
    public func period(calendar: Calendar = .current) -> MonthPeriod {
        let startComponents = DateComponents(year: year, month: month, day: 1, hour: 0, minute: 0, second: 0)
        let start = calendar.date(from: startComponents)!
        let end = calendar.date(byAdding: .month, value: 1, to: start)!
        return MonthPeriod(month: self, startInclusive: start, endExclusive: end)
    }
}

/// 用户时区中的一个月份及其对应的绝对时间边界。
public struct MonthPeriod: Equatable, Sendable {
    public var month: BudgetMonth
    public var startInclusive: Date
    public var endExclusive: Date

    public init(month: BudgetMonth, startInclusive: Date, endExclusive: Date) {
        precondition(startInclusive < endExclusive, "Month period must have a positive duration.")
        self.month = month
        self.startInclusive = startInclusive
        self.endExclusive = endExclusive
    }

    public func contains(_ date: Date) -> Bool {
        date >= startInclusive && date < endExclusive
    }
}

// MARK: - 预算

/// 某个月的总预算（`categoryId == nil`）或分类预算。
public struct Budget: Identifiable, Hashable, Sendable, Codable {
    public var id: String
    public var month: BudgetMonth
    /// 最小货币单位。
    public var amountLimit: Int64
    public var categoryId: String?

    public init(id: String, month: BudgetMonth, amountLimit: Int64, categoryId: String? = nil) {
        precondition(!id.trimmingCharacters(in: .whitespaces).isEmpty, "Budget id must not be blank.")
        precondition(amountLimit >= 0, "Budget amountLimit must not be negative.")
        precondition(categoryId == nil || !categoryId!.trimmingCharacters(in: .whitespaces).isEmpty, "Budget categoryId must be nil or non-blank.")
        self.id = id
        self.month = month
        self.amountLimit = amountLimit
        self.categoryId = categoryId
    }
}

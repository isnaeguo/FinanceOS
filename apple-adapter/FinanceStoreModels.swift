import Foundation
import FinanceOSShared

// MARK: - 显示投影领域类型
//
// 这些类型是 SwiftUI 视图依赖的只读投影：字段与旧 FinanceOSCore 完全一致，内部数值
// 由 KMP shared 提供。删除墓碑（deletedAt != nil）永不进入这里的列表，shared 查询已过滤。

/// 流水的资金方向。
enum TransactionType: String, Codable, Sendable, CaseIterable {
    case income = "INCOME"
    case expense = "EXPENSE"

    var label: String {
        switch self {
        case .income: "收入"
        case .expense: "支出"
        }
    }
}

/// 一笔收入或支出流水（显示投影，金额为最小货币单位）。
struct Transaction: Identifiable, Hashable, Sendable {
    var id: String
    var amount: Int64
    var type: TransactionType
    var categoryId: String
    var accountId: String?
    var dateTime: Date
    var note: String?
    var updatedAt: Int64
    var deletedAt: Int64?

    /// 视图便捷构造：未显式提供同步元数据时视为新记录（写入时会盖上当前时间）。
    init(
        id: String,
        amount: Int64,
        type: TransactionType,
        categoryId: String,
        accountId: String? = nil,
        dateTime: Date,
        note: String? = nil,
        updatedAt: Int64 = 0,
        deletedAt: Int64? = nil
    ) {
        self.id = id
        self.amount = amount
        self.type = type
        self.categoryId = categoryId
        self.accountId = accountId
        self.dateTime = dateTime
        self.note = note
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }
}

/// 分类允许关联的流水方向。
enum CategoryType: String, Codable, Sendable, CaseIterable {
    case income = "INCOME"
    case expense = "EXPENSE"
    case common = "COMMON"

    var label: String {
        switch self {
        case .income: "收入"
        case .expense: "支出"
        case .common: "通用"
        }
    }
}

/// 用于组织流水的业务分类。
struct Category: Identifiable, Hashable, Sendable {
    var id: String
    var name: String
    var type: CategoryType
    var iconKey: String
    var isSystem: Bool
    var updatedAt: Int64
    var deletedAt: Int64?

    var isDeleted: Bool { deletedAt != nil }

    init(
        id: String,
        name: String,
        type: CategoryType,
        iconKey: String,
        isSystem: Bool = false,
        updatedAt: Int64 = 0,
        deletedAt: Int64? = nil
    ) {
        self.id = id
        self.name = name
        self.type = type
        self.iconKey = iconKey
        self.isSystem = isSystem
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }

    func accepts(_ type: TransactionType) -> Bool {
        switch self.type {
        case .income: type == .income
        case .expense: type == .expense
        case .common: true
        }
    }
}

/// FinanceOS 内置分类，固定 ID 与 shared 端一致。
enum DefaultCategories {
    static let all: [Category] = [
        Category(id: "system-food", name: "餐饮", type: .expense, iconKey: "food", isSystem: true, updatedAt: 0),
        Category(id: "system-transport", name: "交通", type: .expense, iconKey: "transport", isSystem: true, updatedAt: 0),
        Category(id: "system-shopping", name: "购物", type: .expense, iconKey: "shopping", isSystem: true, updatedAt: 0),
        Category(id: "system-entertainment", name: "娱乐", type: .expense, iconKey: "entertainment", isSystem: true, updatedAt: 0),
        Category(id: "system-digital", name: "数码", type: .expense, iconKey: "digital", isSystem: true, updatedAt: 0),
        Category(id: "system-learning", name: "学习", type: .expense, iconKey: "learning", isSystem: true, updatedAt: 0),
        Category(id: "system-travel", name: "旅行", type: .expense, iconKey: "travel", isSystem: true, updatedAt: 0),
        Category(id: "system-daily-needs", name: "日用品", type: .expense, iconKey: "daily-needs", isSystem: true, updatedAt: 0),
        Category(id: "system-income", name: "工资/生活费", type: .income, iconKey: "income", isSystem: true, updatedAt: 0),
        Category(id: "system-other", name: "其他", type: .common, iconKey: "other", isSystem: true, updatedAt: 0),
    ]
}

/// 跨平台的预算月份。
struct BudgetMonth: Hashable, Comparable, Sendable {
    var year: Int
    /// 1...12
    var month: Int

    static func < (lhs: BudgetMonth, rhs: BudgetMonth) -> Bool {
        (lhs.year, lhs.month) < (rhs.year, rhs.month)
    }

    var daysInMonth: Int {
        switch month {
        case 2: isLeapYear ? 29 : 28
        case 4, 6, 9, 11: 30
        default: 31
        }
    }

    var isLeapYear: Bool {
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    }

    func next() -> BudgetMonth {
        month == 12 ? BudgetMonth(year: year + 1, month: 1) : BudgetMonth(year: year, month: month + 1)
    }

    func previous() -> BudgetMonth {
        month == 1 ? BudgetMonth(year: year - 1, month: 12) : BudgetMonth(year: year, month: month - 1)
    }

    func period(calendar: Calendar = .current) -> MonthPeriod {
        let startComponents = DateComponents(year: year, month: month, day: 1, hour: 0, minute: 0, second: 0)
        let start = calendar.date(from: startComponents)!
        let end = calendar.date(byAdding: .month, value: 1, to: start)!
        return MonthPeriod(month: self, startInclusive: start, endExclusive: end)
    }
}

/// 用户时区中的一个月份及其对应的绝对时间边界（半开区间）。
struct MonthPeriod: Equatable, Sendable {
    var month: BudgetMonth
    var startInclusive: Date
    var endExclusive: Date

    func contains(_ date: Date) -> Bool {
        date >= startInclusive && date < endExclusive
    }
}

/// 某个月的总预算（`categoryId == nil`）或分类预算。
struct Budget: Identifiable, Hashable, Sendable {
    var id: String
    var month: BudgetMonth
    var amountLimit: Int64
    var categoryId: String?
    var updatedAt: Int64
    var deletedAt: Int64?

    var isDeleted: Bool { deletedAt != nil }

    init(
        id: String,
        month: BudgetMonth,
        amountLimit: Int64,
        categoryId: String? = nil,
        updatedAt: Int64 = 0,
        deletedAt: Int64? = nil
    ) {
        self.id = id
        self.month = month
        self.amountLimit = amountLimit
        self.categoryId = categoryId
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }
}

// MARK: - 计算投影结果

struct MonthlySummary: Equatable, Sendable {
    var totalIncome: Int64
    var totalExpense: Int64
    var netChange: Int64
    var expensesByCategory: [String: Int64]

    var categoryRanking: [(categoryId: String, amount: Int64)] {
        expensesByCategory
            .map { (categoryId: $0.key, amount: $0.value) }
            .sorted { lhs, rhs in
                lhs.amount != rhs.amount ? lhs.amount > rhs.amount : lhs.categoryId < rhs.categoryId
            }
    }
}

struct BudgetUsage: Equatable, Sendable {
    var amountLimit: Int64?
    var amountUsed: Int64
    var amountRemaining: Int64?
    var usageRatio: Double?
    var isOverBudget: Bool
    var hasBudget: Bool
}

struct MonthlyBudgetStatus: Equatable, Sendable {
    var total: BudgetUsage
    var categories: [String: BudgetUsage]
}

struct DailyAvailableBudget: Equatable, Sendable {
    var dailyAmount: Int64
    var amountRemaining: Int64
    var remainingDays: Int
    var isOverBudget: Bool
}

struct ExpenseTrendPoint: Equatable, Identifiable, Sendable {
    var key: String
    var amount: Int64
    var id: String { key }
}

struct ExpenseTrendPeriod: Equatable, Sendable {
    var key: String
    var startInclusive: Date
    var endExclusive: Date
}

struct DailyTrendDatum: Identifiable, Sendable {
    var date: Date
    var amount: Int64
    var id: Date { date }
}

/// 可完整带走的快照与导入结果（显示层用）。
struct FinanceDataSnapshot: Equatable, Sendable {
    var transactions: [Transaction]
    var categories: [Category]
    var budgets: [Budget]
}

struct FinanceDataImportResult: Equatable, Sendable {
    var transactionCount: Int
    var categoryCount: Int
    var budgetCount: Int
}

/// 导入文件无法被安全解释时抛出的可展示错误。
struct DataTransferError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

// MARK: - 时间与金额工具（视图共用，与 shared 测试向量一致）

enum MoneyInput {
    private static let maxMajorDigits = 9

    /// 过滤金额输入，只保留最多两位小数的十进制格式。
    static func normalizeAmountInput(_ rawInput: String) -> String? {
        let normalized = rawInput.replacingOccurrences(of: ",", with: ".")
        if normalized.isEmpty { return normalized }
        // 只允许 digits 与至多一个点与两位小数。
        guard normalized.allSatisfy({ $0.isNumber || $0 == "." }) else { return nil }
        let parts = normalized.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count <= 2 else { return nil }
        guard parts[0].count <= maxMajorDigits, (parts.count == 1 || parts[1].count <= 2) else { return nil }
        return normalized
    }

    /// 将用户输入精确转换为最小货币单位，不经过 Double。
    static func parseAmountInMinorUnits(_ input: String, allowZero: Bool = false) -> Int64? {
        let trimmed = input.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty || trimmed == "." { return nil }
        let parts = trimmed.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count <= 2 else { return nil }
        guard let major = Int64(parts[0].isEmpty ? "0" : String(parts[0])) else { return nil }
        let minor: Int64
        if parts.count > 1 {
            let padded = String(parts[1]).padding(toLength: 2, withPad: "0", startingAt: 0)
            guard let value = Int64(padded.prefix(2)) else { return nil }
            minor = value
        } else {
            minor = 0
        }
        let (amount, didOverflow) = major.multipliedReportingOverflow(by: 100)
        if didOverflow { return nil }
        let (total, didUnderflow) = amount.addingReportingOverflow(minor)
        if didUnderflow { return nil }
        return total > 0 || (allowZero && total == 0) ? total : nil
    }

    /// 统一的人民币符号、千位分隔和两位小数。
    static func formatMoney(_ amountMinor: Int64) -> String {
        let magnitude = abs(amountMinor)
        let major = String(magnitude / 100)
        var grouped = ""
        for (offset, character) in major.reversed().enumerated() {
            if offset > 0 && offset % 3 == 0 { grouped.insert(",", at: grouped.startIndex) }
            grouped.insert(character, at: grouped.startIndex)
        }
        let minor = String(format: "%02lld", magnitude % 100)
        return "\(amountMinor < 0 ? "-" : "")¥\(grouped).\(minor)"
    }
}

func formatMoney(_ amountMinor: Int64) -> String { MoneyInput.formatMoney(amountMinor) }
func parseAmountInMinorUnits(_ input: String, allowZero: Bool = false) -> Int64? {
    MoneyInput.parseAmountInMinorUnits(input, allowZero: allowZero)
}
func normalizeAmountInput(_ rawInput: String) -> String? { MoneyInput.normalizeAmountInput(rawInput) }

// MARK: - 计算器（纯 Swift 显示投影；输入为当前内存中的可见数据）

enum SummaryCalculations {
    static func monthlySummary(transactions: [Transaction]) -> MonthlySummary {
        var totalIncome: Int64 = 0
        var totalExpense: Int64 = 0
        var expensesByCategory: [String: Int64] = [:]
        for transaction in transactions {
            switch transaction.type {
            case .income:
                totalIncome = add(totalIncome, transaction.amount)
            case .expense:
                totalExpense = add(totalExpense, transaction.amount)
                expensesByCategory[transaction.categoryId] = add(
                    expensesByCategory[transaction.categoryId] ?? 0,
                    transaction.amount
                )
            }
        }
        return MonthlySummary(
            totalIncome: totalIncome,
            totalExpense: totalExpense,
            netChange: totalIncome - totalExpense,
            expensesByCategory: expensesByCategory
        )
    }

    static func add(_ current: Int64, _ amount: Int64) -> Int64 {
        precondition(amount <= Int64.max - current, "Monthly money total exceeds Int64 range.")
        return current + amount
    }

    static func budgetUsage(budget: Budget?, amountUsed: Int64) -> BudgetUsage {
        // amountUsed 允许为负：月总预算按净支出统计，收入大于支出时有结余即不为超支。
        guard let budget else {
            return BudgetUsage(amountLimit: nil, amountUsed: amountUsed, amountRemaining: nil, usageRatio: nil, isOverBudget: false, hasBudget: false)
        }
        let usageRatio: Double? = budget.amountLimit == 0 ? nil : Double(amountUsed) / Double(budget.amountLimit)
        return BudgetUsage(
            amountLimit: budget.amountLimit,
            amountUsed: amountUsed,
            amountRemaining: budget.amountLimit - amountUsed,
            usageRatio: usageRatio,
            isOverBudget: amountUsed > budget.amountLimit,
            hasBudget: true
        )
    }

    static func budgetStatus(summary: MonthlySummary, budgets: [Budget]) -> MonthlyBudgetStatus {
        let totalBudget = budgets.first { $0.categoryId == nil }
        var categoryUsages: [String: BudgetUsage] = [:]
        for budget in budgets {
            guard let categoryId = budget.categoryId else { continue }
            categoryUsages[categoryId] = budgetUsage(budget: budget, amountUsed: summary.expensesByCategory[categoryId] ?? 0)
        }
        return MonthlyBudgetStatus(
            // 月总预算按净支出（支出 − 收入）统计：收入进账抵扣预算消耗，结余即未用完。
            total: budgetUsage(budget: totalBudget, amountUsed: summary.totalExpense - summary.totalIncome),
            categories: categoryUsages
        )
    }

    static func dailyAvailableBudget(
        period: MonthPeriod,
        currentDayOfMonth: Int,
        startOfToday: Date,
        totalBudget: Budget?,
        transactions: [Transaction]
    ) -> DailyAvailableBudget? {
        let daysInMonth = period.month.daysInMonth
        precondition((1...daysInMonth).contains(currentDayOfMonth), "Current day must be valid for the budget month.")
        precondition(period.contains(startOfToday), "Start of today must be inside the budget month.")
        guard let totalBudget else { return nil }

        var amountUsedBeforeToday: Int64 = 0
        for transaction in transactions where transaction.type == .expense && transaction.dateTime < startOfToday {
            precondition(transaction.amount <= Int64.max - amountUsedBeforeToday, "Daily budget expense total exceeds Int64 range.")
            amountUsedBeforeToday += transaction.amount
        }

        let amountRemaining = totalBudget.amountLimit - amountUsedBeforeToday
        let remainingDays = daysInMonth - currentDayOfMonth + 1
        let dailyAmount = max(0, amountRemaining) / Int64(remainingDays)
        return DailyAvailableBudget(
            dailyAmount: dailyAmount,
            amountRemaining: amountRemaining,
            remainingDays: remainingDays,
            isOverBudget: amountRemaining < 0
        )
    }

    static func expenseTrend(periods: [ExpenseTrendPeriod], transactions: [Transaction]) -> [ExpenseTrendPoint] {
        // 趋势与首页「支出」/月总预算同口径：净支出 = 支出 − 收入，收入≥支出时为负（有结余）。
        periods.map { period in
            var expense: Int64 = 0
            var income: Int64 = 0
            for transaction in transactions
            where transaction.dateTime >= period.startInclusive &&
                transaction.dateTime < period.endExclusive {
                switch transaction.type {
                case .expense:
                    precondition(transaction.amount <= Int64.max - expense, "Expense trend total exceeds Int64 range.")
                    expense += transaction.amount
                case .income:
                    precondition(transaction.amount <= Int64.max - income, "Expense trend total exceeds Int64 range.")
                    income += transaction.amount
                }
            }
            return ExpenseTrendPoint(key: period.key, amount: expense - income)
        }
    }

    static func monthTrendPeriods(anchorMonth: BudgetMonth, count: Int, calendar: Calendar = .current) -> [ExpenseTrendPeriod] {
        var months: [BudgetMonth] = []
        var current = anchorMonth
        for _ in 0..<max(0, count) {
            months.append(current)
            current = current.previous()
        }
        return months.reversed().map { month in
            let period = month.period(calendar: calendar)
            return ExpenseTrendPeriod(key: "\(month.year)-\(String(format: "%02d", month.month))", startInclusive: period.startInclusive, endExclusive: period.endExclusive)
        }
    }

    static func dailyTrendPeriods(anchorDay: Date, days: Int, calendar: Calendar = .current) -> [ExpenseTrendPeriod] {
        let dayCount = max(1, days)
        let startOfAnchorDay = calendar.startOfDay(for: anchorDay)
        return (0..<dayCount).reversed().compactMap { offset in
            guard let start = calendar.date(byAdding: .day, value: -offset, to: startOfAnchorDay),
                  let end = calendar.date(byAdding: .day, value: 1, to: start) else { return nil }
            let label = calendar.dateComponents([.month, .day], from: start)
            let key = String(format: "%02d-%02d", label.month ?? 0, label.day ?? 0)
            return ExpenseTrendPeriod(key: key, startInclusive: start, endExclusive: end)
        }
    }
}

// MARK: - KMP 时间转换

extension Date {
    init(epochMillis: Int64) {
        self = Date(timeIntervalSince1970: Double(epochMillis) / 1000)
    }

    var epochMillis: Int64 {
        Int64((timeIntervalSince1970 * 1000).rounded())
    }
}

enum KMPBridge {
    static func instant(from date: Date) -> KotlinInstant {
        KotlinInstant.Companion().fromEpochMilliseconds(epochMilliseconds: date.epochMillis)
    }

    static func date(from instant: KotlinInstant) -> Date {
        Date(epochMillis: instant.toEpochMilliseconds())
    }
}

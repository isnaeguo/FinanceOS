import Foundation

// MARK: - 月度汇总

/// 指定月份的收支汇总，所有金额均为最小货币单位。
public struct MonthlySummary: Equatable, Sendable {
    public var totalIncome: Int64
    public var totalExpense: Int64
    public var netChange: Int64
    public var expensesByCategory: [String: Int64]

    /// 分类支出排行，金额从高到低。
    public var categoryRanking: [(categoryId: String, amount: Int64)] {
        expensesByCategory
            .map { (categoryId: $0.key, amount: $0.value) }
            .sorted { lhs, rhs in
                lhs.amount != rhs.amount ? lhs.amount > rhs.amount : lhs.categoryId < rhs.categoryId
            }
    }
}

public enum MonthlySummaryCalculator {
    /// 根据月流水计算收入、支出、净变化及分类支出。
    public static func calculate(_ transactions: [Transaction]) -> MonthlySummary {
        var totalIncome: Int64 = 0
        var totalExpense: Int64 = 0
        var expensesByCategory: [String: Int64] = [:]

        for transaction in transactions {
            switch transaction.type {
            case .income:
                totalIncome = addMoney(totalIncome, transaction.amount)
            case .expense:
                totalExpense = addMoney(totalExpense, transaction.amount)
                expensesByCategory[transaction.categoryId] = addMoney(
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

    public static func addMoney(_ current: Int64, _ amount: Int64) -> Int64 {
        // 金额使用 Int64 累加时显式阻止溢出，避免汇总结果悄悄变成负数。
        precondition(amount <= Int64.max - current, "Monthly money total exceeds Int64 range.")
        return current + amount
    }
}

// MARK: - 预算计算

/// 一次纯业务预算计算的结果。
///
/// 没有设置预算时，`amountRemaining` 和 `usageRatio` 为 `nil`，调用方可通过 `hasBudget` 明确区分。
public struct BudgetUsage: Equatable, Sendable {
    public var amountLimit: Int64?
    public var amountUsed: Int64
    public var amountRemaining: Int64?
    /// 无量纲比例，不用于保存或计算货币金额，因此可以安全使用 Double。
    public var usageRatio: Double?
    public var isOverBudget: Bool
    public var hasBudget: Bool
}

public enum BudgetCalculator {
    /// 计算预算的已使用金额、剩余金额、使用比例和超支状态。
    public static func calculate(budget: Budget?, amountUsed: Int64) -> BudgetUsage {
        precondition(amountUsed >= 0, "Budget amountUsed must not be negative.")

        guard let budget else {
            // 没有预算就不存在可比较的额度，因此不虚构剩余金额、比例或超支状态。
            return BudgetUsage(
                amountLimit: nil,
                amountUsed: amountUsed,
                amountRemaining: nil,
                usageRatio: nil,
                isOverBudget: false,
                hasBudget: false
            )
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
}

/// 月总预算以及已设置分类预算的使用情况。
public struct MonthlyBudgetStatus: Equatable, Sendable {
    public var total: BudgetUsage
    public var categories: [String: BudgetUsage]
}

public enum BudgetStatusCalculator {
    /// 将当月支出汇总与总预算、分类预算进行比较。
    public static func calculate(summary: MonthlySummary, budgets: [Budget]) -> MonthlyBudgetStatus {
        let totalBudget = budgets.first { $0.categoryId == nil }
        var categoryUsages: [String: BudgetUsage] = [:]
        for budget in budgets {
            guard let categoryId = budget.categoryId else { continue }
            categoryUsages[categoryId] = BudgetCalculator.calculate(
                budget: budget,
                amountUsed: summary.expensesByCategory[categoryId] ?? 0
            )
        }
        return MonthlyBudgetStatus(
            total: BudgetCalculator.calculate(budget: totalBudget, amountUsed: summary.totalExpense),
            categories: categoryUsages
        )
    }
}

// MARK: - 每日可用预算

/// 当天零点确定、当天内保持不变的建议日预算。
public struct DailyAvailableBudget: Equatable, Sendable {
    public var dailyAmount: Int64
    /// 当天零点时尚未分配的月预算，不包含当天发生的支出。
    public var amountRemaining: Int64
    public var remainingDays: Int
    public var isOverBudget: Bool
}

public enum DailyAvailableBudgetCalculator {
    /// 根据当天零点前的累计支出，计算包含当天在内的建议日预算。
    public static func calculate(
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
        // 当天仍可消费，因此剩余天数包含当天；月末当天固定为 1 天。
        let remainingDays = daysInMonth - currentDayOfMonth + 1
        // 只统计今日零点前的支出，所以当天新增或删除流水不会反复改变这次分配。
        // 超支后没有正的建议金额；整除向下舍弃不足一个最小货币单位的余数。
        let dailyAmount = max(0, amountRemaining) / Int64(remainingDays)

        return DailyAvailableBudget(
            dailyAmount: dailyAmount,
            amountRemaining: amountRemaining,
            remainingDays: remainingDays,
            isOverBudget: amountRemaining < 0
        )
    }
}

// MARK: - 支出趋势

/// 一个用于趋势聚合的半开时间桶。
public struct ExpenseTrendPeriod: Equatable, Sendable {
    public var key: String
    public var startInclusive: Date
    public var endExclusive: Date

    public init(key: String, startInclusive: Date, endExclusive: Date) {
        precondition(!key.trimmingCharacters(in: .whitespaces).isEmpty, "Expense trend key must not be blank.")
        precondition(startInclusive < endExclusive, "Expense trend period must have a positive duration.")
        self.key = key
        self.startInclusive = startInclusive
        self.endExclusive = endExclusive
    }
}

/// 一个时间桶内的支出总额，金额继续使用最小货币单位。
public struct ExpenseTrendPoint: Equatable, Identifiable, Sendable {
    public var key: String
    public var amount: Int64

    public var id: String { key }
}

/// 按日趋势数据点，携带桶起始日期便于图表连续轴布局。
public struct DailyTrendDatum: Identifiable, Sendable {
    public var date: Date
    public var amount: Int64

    public var id: Date { date }
}

public enum ExpenseTrendCalculator {
    /// 在已读取的流水快照上按时间桶聚合支出趋势，不进行存储访问。
    public static func calculate(periods: [ExpenseTrendPeriod], transactions: [Transaction]) -> [ExpenseTrendPoint] {
        periods.map { period in
            var amount: Int64 = 0
            for transaction in transactions
            where transaction.type == .expense &&
                transaction.dateTime >= period.startInclusive &&
                transaction.dateTime < period.endExclusive {
                // 趋势同样属于财务汇总，溢出时必须失败，不能悄悄显示成负数。
                precondition(transaction.amount <= Int64.max - amount, "Expense trend total exceeds Int64 range.")
                amount += transaction.amount
            }
            return ExpenseTrendPoint(key: period.key, amount: amount)
        }
    }

    /// 最近 `count` 个自然月的月度趋势桶，最后一个桶是 `anchorMonth` 所在的当月。
    public static func monthTrendPeriods(anchorMonth: BudgetMonth, count: Int, calendar: Calendar = .current) -> [ExpenseTrendPeriod] {
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

    /// 以 `anchorDay` 为最后一天的按日趋势桶（包含当天），返回按时间升序的桶。
    public static func dailyTrendPeriods(anchorDay: Date, days: Int, calendar: Calendar = .current) -> [ExpenseTrendPeriod] {
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

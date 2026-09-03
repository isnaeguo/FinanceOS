import Foundation
import FinanceOSShared

/// 小组件数据源：直接从 App Group 内的 Room 库读取当月概览，废弃对 store.json 的依赖。
///
/// 小组件与 App 共用同一数据库文件（位置策略与 App 一致：App Group 优先、Application Support
/// 回退）。删除墓碑不会进入本数据：shared 查询已过滤。
enum WidgetDataLoader {
    struct MonthlyMetrics {
        let month: BudgetMonth
        let usedMinor: Int64
        let dailyMinor: Int64?
        let remainingMinor: Int64?
        let isOverBudget: Bool

        var hasBudget: Bool { dailyMinor != nil }
    }

    /// 读取当前月份概览；没有预算或没有数据时返回 nil 语义由调用方按 hasBudget/空态处理。
    static func loadMetrics(now: Date = Date()) async throws -> MonthlyMetrics? {
        let db = AppleDatabaseLocationKt.createAppleFinanceOsDatabase(path: nil)
        let repository = LocalFinanceDataRepository(database: db)
        let full = try await repository.snapshot()
        let calendar = Calendar.current
        let components = calendar.dateComponents([.year, .month], from: now)
        guard let year = components.year, let monthNumber = components.month else { return nil }
        let month = BudgetMonth(year: year, month: monthNumber)
        let period = month.period(calendar: calendar)

        let transactions = full.transactions
            .compactMap { transaction -> Transaction? in
                guard transaction.deletedAt == nil else { return nil }
                return Transaction(kmp: transaction)
            }
        let visible = transactions.filter { period.contains($0.dateTime) }
        let summary = SummaryCalculations.monthlySummary(transactions: visible)
        let budgets = full.budgets
            .compactMap { budget -> Budget? in
                guard budget.deletedAt == nil else { return nil }
                let monthValue = BudgetMonth(year: Int(budget.month.year), month: Int(budget.month.month))
                guard monthValue == month else { return nil }
                return Budget(
                    id: budget.id,
                    month: monthValue,
                    amountLimit: budget.amountLimit,
                    categoryId: budget.categoryId,
                    updatedAt: budget.updatedAt,
                    deletedAt: budget.deletedAt?.int64Value
                )
            }
        let status = SummaryCalculations.budgetStatus(summary: summary, budgets: budgets)
        guard status.total.hasBudget else {
            return MonthlyMetrics(
                month: month,
                usedMinor: summary.totalExpense,
                dailyMinor: nil,
                remainingMinor: nil,
                isOverBudget: false
            )
        }
        let day = calendar.component(.day, from: now)
        let daily = SummaryCalculations.dailyAvailableBudget(
            period: period,
            currentDayOfMonth: day,
            startOfToday: calendar.startOfDay(for: now),
            totalBudget: budgets.first { $0.categoryId == nil },
            transactions: visible
        )
        return MonthlyMetrics(
            month: month,
            usedMinor: summary.totalExpense,
            dailyMinor: daily?.dailyAmount,
            remainingMinor: daily?.amountRemaining,
            isOverBudget: daily?.isOverBudget ?? status.total.isOverBudget
        )
    }
}

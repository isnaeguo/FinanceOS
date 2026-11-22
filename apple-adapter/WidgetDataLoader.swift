import Foundation
import FinanceOSShared

/// 从共享 Room 数据库读取当月小组件数据。
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
        let calendar = Calendar.current
        let components = calendar.dateComponents([.year, .month, .day], from: now)
        guard let year = components.year, let monthNumber = components.month, let day = components.day else { return nil }
        let month = BudgetMonth(year: year, month: monthNumber)
        let period = month.period(calendar: calendar)
        let databasePath = try AppleDatabaseLocationKt.defaultAppleFinanceOsDatabasePath()
        let database = AppleDatabaseLocationKt.createAppleFinanceOsDatabase(path: databasePath)
        defer { database.close() }
        let overview = try await LocalFinanceDataRepository(database: database).monthlyBudgetOverview(
            period: FinanceOSShared.MonthPeriod(
                month: FinanceOSShared.BudgetMonth(year: Int32(year), month: Int32(monthNumber)),
                startInclusive: KMPBridge.instant(from: period.startInclusive),
                endExclusive: KMPBridge.instant(from: period.endExclusive)
            ),
            dayOfMonth: Int32(day),
            startOfToday: KMPBridge.instant(from: calendar.startOfDay(for: now))
        )
        return MonthlyMetrics(
            month: month,
            usedMinor: overview.amountUsed,
            dailyMinor: overview.dailyAvailable?.int64Value,
            remainingMinor: overview.amountRemaining?.int64Value,
            isOverBudget: overview.isOverBudget
        )
    }
}

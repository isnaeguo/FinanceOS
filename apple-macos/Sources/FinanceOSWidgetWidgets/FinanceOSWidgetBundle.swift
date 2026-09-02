import Foundation
import SwiftUI
import WidgetKit

// MARK: - 小组件入口

/// 单一小组件：kind 为 "FinanceOSWidget"，支持小 / 中 / 大三种尺寸。
/// 领域模型与计算（Model / Calculations / Money / Transfers）由构建脚本与 Domain 目录一同编译，
/// 保证与 App 内的 FinanceOSCore 逻辑完全一致。
@main
struct FinanceOSWidgetBundle: WidgetBundle {
    var body: some Widget {
        FinanceOSWidget()
    }
}

struct FinanceOSWidget: Widget {
    let kind = "FinanceOSWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: FinanceOSWidgetProvider()) { entry in
            FinanceOSWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("FinanceOS 本月概览")
        .description("查看本月已用、每日可用与剩余预算。")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// MARK: - 时间线条目

struct FinanceOSWidgetEntry: TimelineEntry {
    let date: Date
    /// 本次读取到的当月数据；为 nil 表示没有数据文件或读取失败，进入空状态。
    let snapshot: FinanceOSWidgetSnapshot?
    /// 空状态的提示文案。
    let message: String
}

/// 从数据文件解析出来的“本月概览”纯数据，全部金额为最小货币单位。
struct FinanceOSWidgetSnapshot {
    let month: BudgetMonth
    let usedMinor: Int64
    let dailyMinor: Int64?
    let remainingMinor: Int64?
    let isOverBudget: Bool

    var hasBudget: Bool { dailyMinor != nil }
}

// MARK: - TimelineProvider

/// 每次刷新时立即读取一次数据文件，返回单条 entry，下一条时间线定在一小时后。
/// 读取文件不发生在主线程：这里使用后台任务计算，再回到主线程回调 WidgetKit。
struct FinanceOSWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> FinanceOSWidgetEntry {
        FinanceOSWidgetEntry(date: Date(), snapshot: nil, message: "本月概览")
    }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (FinanceOSWidgetEntry) -> Void) {
        Self.computeAsync(completion: completion)
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<FinanceOSWidgetEntry>) -> Void) {
        Self.computeAsync { entry in
            let nextRefresh = Date().addingTimeInterval(60 * 60)
            completion(Timeline(entries: [entry], policy: .after(nextRefresh)))
        }
    }

    /// 后台读取数据文件并计算，完成后再回到主线程回调。
    private static func computeAsync(completion: @escaping @Sendable (FinanceOSWidgetEntry) -> Void) {
        Task.detached(priority: .userInitiated) {
            let entry = Self.loadEntry()
            await MainActor.run {
                completion(entry)
            }
        }
    }

    // MARK: - 数据读取与计算

    private static func loadEntry() -> FinanceOSWidgetEntry {
        do {
            guard let snapshot = try readSnapshot() else {
                return FinanceOSWidgetEntry(
                    date: Date(),
                    snapshot: nil,
                    message: "暂无数据，先打开 App 记一笔吧"
                )
            }
            return FinanceOSWidgetEntry(date: Date(), snapshot: snapshot, message: "")
        } catch {
            return FinanceOSWidgetEntry(
                date: Date(),
                snapshot: nil,
                message: "数据暂时无法读取"
            )
        }
    }

    /// 与 App 共用同一数据文件：`~/Library/Application Support/FinanceOS/store.json`。
    private static func readSnapshot() throws -> FinanceOSWidgetSnapshot? {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("FinanceOS", isDirectory: true).appendingPathComponent("store.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let content = try String(contentsOf: url, encoding: .utf8)
        let data = try FinanceDataJsonCodec.decode(content)
        return compute(data: data, now: Date())
    }

    private static func compute(data: FinanceDataSnapshot, now: Date) -> FinanceOSWidgetSnapshot {
        let calendar = Calendar.current
        let components = calendar.dateComponents([.year, .month, .day], from: now)
        let month = BudgetMonth(year: components.year ?? 1970, month: components.month ?? 1)
        let period = month.period(calendar: calendar)

        // 当月流水（半开区间过滤），复用同一套月度汇总与每日可用预算算法。
        let monthTransactions = data.transactions.filter { period.contains($0.dateTime) }
        let used = MonthlySummaryCalculator.calculate(monthTransactions).totalExpense
        let totalBudget = data.budgets.first { $0.categoryId == nil && $0.month == month }
        let startOfToday = calendar.startOfDay(for: now)
        let daily = DailyAvailableBudgetCalculator.calculate(
            period: period,
            currentDayOfMonth: components.day ?? 1,
            startOfToday: startOfToday,
            totalBudget: totalBudget,
            transactions: monthTransactions
        )

        guard let totalBudget else {
            return FinanceOSWidgetSnapshot(
                month: month,
                usedMinor: used,
                dailyMinor: nil,
                remainingMinor: nil,
                isOverBudget: false
            )
        }
        let remaining = totalBudget.amountLimit - used
        return FinanceOSWidgetSnapshot(
            month: month,
            usedMinor: used,
            dailyMinor: daily?.dailyAmount,
            remainingMinor: remaining,
            isOverBudget: remaining < 0
        )
    }
}

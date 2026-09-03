import Foundation
import SwiftUI
import WidgetKit

// MARK: - 小组件入口

/// 单一小组件：kind 为 "FinanceOSWidget"，支持小 / 中 / 大三种尺寸。
/// 数据直接读取 shared（Room，App Group 内库文件），不再依赖 store.json。
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
    /// 本次读取到的当月数据；为 nil 表示读取失败或没有本月总预算，进入空状态。
    let snapshot: FinanceOSWidgetSnapshot?
    /// 空状态的提示文案。
    let message: String
}

/// 从共享数据库解析出来的“本月概览”纯数据，全部金额为最小货币单位。
struct FinanceOSWidgetSnapshot {
    let month: BudgetMonth
    let usedMinor: Int64
    let dailyMinor: Int64?
    let remainingMinor: Int64?
    let isOverBudget: Bool

    var hasBudget: Bool { dailyMinor != nil }
}

// MARK: - TimelineProvider

/// 每次刷新时经 shared 读取一次 Room 数据库，返回单条 entry，下一条时间线定在一小时后。
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

    /// 后台经 shared 读取并计算，完成后再回到主线程回调 WidgetKit。
    private static func computeAsync(completion: @escaping @Sendable (FinanceOSWidgetEntry) -> Void) {
        Task {
            let entry = await Self.loadEntry()
            await MainActor.run {
                completion(entry)
            }
        }
    }

    private static func loadEntry() async -> FinanceOSWidgetEntry {
        do {
            guard let metrics = try await WidgetDataLoader.loadMetrics(now: Date()) else {
                return FinanceOSWidgetEntry(date: Date(), snapshot: nil, message: "暂无数据，先打开 App 记一笔吧")
            }
            let snapshot = FinanceOSWidgetSnapshot(
                month: metrics.month,
                usedMinor: metrics.usedMinor,
                dailyMinor: metrics.dailyMinor,
                remainingMinor: metrics.remainingMinor,
                isOverBudget: metrics.isOverBudget
            )
            return FinanceOSWidgetEntry(date: Date(), snapshot: snapshot, message: "")
        } catch {
            return FinanceOSWidgetEntry(date: Date(), snapshot: nil, message: "数据暂时无法读取")
        }
    }
}

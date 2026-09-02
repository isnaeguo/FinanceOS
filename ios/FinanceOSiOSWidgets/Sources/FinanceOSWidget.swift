import WidgetKit
import SwiftUI
#if os(iOS)
import UIKit
#endif

// 小组件与 App 复用同一 Core（共享编译源），读取 App Group 容器内的 store.json；
// 计算口径与 App/Widget 的“本月已用 / 每日可用 / 本月剩余”完全一致。


extension Color {
    static var widgetBackground: Color {
        #if os(iOS)
        Color(UIColor.systemBackground)
        #else
        Color(white: 1)
        #endif
    }
}

struct WidgetMetrics: Equatable {
    let usedMinor: Int64
    let dailyMinor: Int64?
    let remainingMinor: Int64?
    let hasBudget: Bool
    let isOver: Bool
    let monthYear: Int
    let monthNumber: Int
    let updatedText: String
}

struct FinanceOSEntry: TimelineEntry {
    let date: Date
    let metrics: WidgetMetrics?
}

struct FinanceOSProvider: TimelineProvider {
    func placeholder(in context: Context) -> FinanceOSEntry {
        FinanceOSEntry(date: Date(), metrics: sample())
    }

    func getSnapshot(in context: Context, completion: @escaping (FinanceOSEntry) -> Void) {
        completion(FinanceOSEntry(date: Date(), metrics: context.isPreview ? sample() : loadMetrics()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<FinanceOSEntry>) -> Void) {
        let entry = FinanceOSEntry(date: Date(), metrics: loadMetrics())
        let next = Calendar.current.date(byAdding: .hour, value: 1, to: Date()) ?? Date().addingTimeInterval(3600)
        completion(Timeline(entries: [entry], policy: .after(next)))
    }

    private func sample() -> WidgetMetrics {
        WidgetMetrics(
            usedMinor: 1862400,
            dailyMinor: 68000,
            remainingMinor: 1437500,
            hasBudget: true,
            isOver: false,
            monthYear: 2026,
            monthNumber: 9,
            updatedText: "9月2日 更新"
        )
    }

    private func loadMetrics() -> WidgetMetrics? {
        let calendar = Calendar.current
        let today = Date()
        let month = BudgetMonth(
            year: calendar.component(.year, from: today),
            month: calendar.component(.month, from: today)
        )
        let period = month.period(calendar: calendar)

        let url = DefaultStoreLocation().storeURL()
        guard let data = try? Data(contentsOf: url) else { return nil }
        guard let snapshot = try? FinanceDataJsonCodec.decode(String(decoding: data, as: UTF8.self)) else { return nil }

        let transactions = snapshot.transactions.filter { period.contains($0.dateTime) }
        let summary = MonthlySummaryCalculator.calculate(transactions)
        let totalBudget = snapshot.budgets.first { $0.month == month && $0.categoryId == nil }

        let usage = BudgetCalculator.calculate(budget: totalBudget, amountUsed: summary.totalExpense)
        let startOfToday = calendar.startOfDay(for: today)
        let day = calendar.component(.day, from: today)
        let daily = DailyAvailableBudgetCalculator.calculate(
            period: period,
            currentDayOfMonth: day,
            startOfToday: startOfToday,
            totalBudget: totalBudget,
            transactions: transactions
        )

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "M月d日 更新"

        return WidgetMetrics(
            usedMinor: summary.totalExpense,
            dailyMinor: daily?.dailyAmount,
            remainingMinor: usage.amountRemaining,
            hasBudget: usage.hasBudget,
            isOver: usage.isOverBudget,
            monthYear: month.year,
            monthNumber: month.month,
            updatedText: formatter.string(from: today)
        )
    }
}

struct FinanceOSWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: FinanceOSEntry

    var body: some View {
        if let metrics = entry.metrics {
            switch family {
            case .systemSmall: small(metrics)
            case .systemMedium: medium(metrics)
            case .systemLarge: large(metrics)
            default: medium(metrics)
            }
        } else {
            Text("打开 FinanceOS 后重试")
                .containerBackground(for: .widget) { Color.widgetBackground }
        }
    }

    private func amount(_ minor: Int64?) -> String {
        minor.map { formatMoney($0) } ?? "--"
    }

    private func monthText(_ metrics: WidgetMetrics) -> String {
        "\(metrics.monthYear)年\(metrics.monthNumber)月"
    }

    private func small(_ metrics: WidgetMetrics) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("本月已用")
                .font(.caption).foregroundStyle(.secondary)
            Text(amount(metrics.usedMinor))
                .font(.system(size: 18, weight: .bold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1).minimumScaleFactor(0.6)
            Spacer(minLength: 0)
            valueLine("每日可用", amount(metrics.dailyMinor), .teal, weight: .semibold)
            valueLine("本月剩余", amount(metrics.remainingMinor),
                      metrics.isOver ? .red : .green, weight: .semibold)
            Text(metrics.updatedText)
                .font(.system(size: 9)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .containerBackground(for: .widget) { Color.widgetBackground }
    }

    private func medium(_ metrics: WidgetMetrics) -> some View {
        HStack(spacing: 12) {
            metric("本月已用", amount(metrics.usedMinor), .secondary)
            metric("每日可用", amount(metrics.dailyMinor), .teal)
            metric("本月剩余", amount(metrics.remainingMinor), metrics.isOver ? .red : .green)
        }
        .frame(maxWidth: .infinity)
        .containerBackground(for: .widget) { Color.widgetBackground }
    }

    private func large(_ metrics: WidgetMetrics) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(monthText(metrics))
                    .font(.headline)
                Spacer()
                if metrics.hasBudget {
                    Text("本月预算 \(amount(metrics.usedMinor + (metrics.remainingMinor ?? 0)))")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            if metrics.hasBudget {
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(.quaternary)
                        if let ratio = usageRatio(metrics) {
                            Capsule()
                                .fill(metrics.isOver ? Color.red : Color.teal)
                                .frame(width: max(0, min(1, ratio)) * proxy.size.width)
                        }
                    }
                }
                .frame(height: 8)
            }
            HStack(spacing: 12) {
                metric("本月已用", amount(metrics.usedMinor), .secondary)
                metric("每日可用", amount(metrics.dailyMinor), .teal)
                metric("本月剩余", amount(metrics.remainingMinor), metrics.isOver ? .red : .green)
            }
            Text(metrics.updatedText)
                .font(.caption).foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .containerBackground(for: .widget) { Color.widgetBackground }
    }

    private func usageRatio(_ metrics: WidgetMetrics) -> Double? {
        guard metrics.hasBudget, let remaining = metrics.remainingMinor else { return nil }
        let limit = metrics.usedMinor + remaining
        guard limit > 0 else { return nil }
        return Double(metrics.usedMinor) / Double(limit)
    }

    private func metric(_ title: String, _ value: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption2).foregroundStyle(.secondary)
            Text(value)
                .font(.system(size: 13, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(color)
                .lineLimit(1).minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func valueLine(_ title: String, _ value: String, _ color: Color, weight: Font.Weight = .regular) -> some View {
        HStack {
            Text(title).font(.system(size: 10)).foregroundStyle(.secondary)
            Spacer(minLength: 2)
            Text(value)
                .font(.system(size: 11, weight: weight, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(color)
                .lineLimit(1).minimumScaleFactor(0.7)
        }
    }
}

struct FinanceOSWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "FinanceOSWidget", provider: FinanceOSProvider()) { entry in
            FinanceOSWidgetView(entry: entry)
        }
        .configurationDisplayName("FinanceOS 本月概览")
        .description("本月已用 / 每日可用 / 本月剩余")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@main
struct FinanceOSWidgetBundle: WidgetBundle {
    var body: some Widget {
        FinanceOSWidget()
    }
}

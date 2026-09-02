import SwiftUI
import WidgetKit

// MARK: - 本月概览小组件视图

/// 按 widgetFamily 布局本月概览。金额格式直接复用核心的 formatMoney（随 Domain 一起编译进来）。
struct FinanceOSWidgetEntryView: View {
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var colorScheme

    let entry: FinanceOSWidgetEntry

    var body: some View {
        Group {
            if let snapshot = entry.snapshot {
                switch family {
                case .systemSmall:
                    SmallWidgetView(snapshot: snapshot, entry: entry)
                case .systemMedium:
                    MediumWidgetView(snapshot: snapshot, entry: entry)
                case .systemLarge:
                    LargeWidgetView(snapshot: snapshot, entry: entry)
                default:
                    SmallWidgetView(snapshot: snapshot, entry: entry)
                }
            } else {
                EmptyWidgetView(message: entry.message)
            }
        }
        .containerBackground(for: .widget) {
            backgroundGradient
        }
    }

    private var backgroundGradient: LinearGradient {
        if colorScheme == .dark {
            return LinearGradient(
                colors: [Color(red: 0.08, green: 0.10, blue: 0.18), Color(red: 0.10, green: 0.20, blue: 0.22)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }
        return LinearGradient(
            colors: [Color.white.opacity(0.92), Color(red: 0.88, green: 0.95, blue: 0.97)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}

// MARK: - 小尺寸

private struct SmallWidgetView: View {
    let snapshot: FinanceOSWidgetSnapshot
    let entry: FinanceOSWidgetEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("本月已用")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(formatMoney(snapshot.usedMinor))
                .font(.system(size: 24, weight: .bold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            Spacer(minLength: 2)
            if snapshot.hasBudget {
                if let daily = snapshot.dailyMinor {
                    Text("每日可用 \(formatMoney(daily))")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.teal)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                if let remaining = snapshot.remainingMinor {
                    remainingLabel(remaining)
                }
            } else {
                Text("设置预算后显示日均/剩余")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 0)
            Text(Self.updatedText(entry.date))
                .font(.caption2)
                .foregroundStyle(.secondary.opacity(0.8))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private func remainingLabel(_ remaining: Int64) -> some View {
        if snapshot.isOverBudget {
            return Text("已超支 \(formatMoney(-remaining))")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.red)
                .lineLimit(1)
        }
        return Text("本月剩余 \(formatMoney(remaining))")
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(.green)
            .lineLimit(1)
    }

    static func updatedText(_ date: Date) -> String {
        let calendar = Calendar.current
        let components = calendar.dateComponents([.month, .day], from: date)
        return "\(components.month ?? 0)月\(components.day ?? 0)日 更新"
    }
}

// MARK: - 大尺寸（含标题与预算进度）

private struct LargeWidgetView: View {
    let snapshot: FinanceOSWidgetSnapshot
    let entry: FinanceOSWidgetEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("FinanceOS · \(snapshot.month.month)月")
                    .font(.headline)
                Spacer()
                Text(SmallWidgetView.updatedText(entry.date))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if snapshot.hasBudget {
                budgetProgress
            } else {
                Text("设置月预算后显示每日可用与剩余预算")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            statBlocks
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var budgetProgress: some View {
        VStack(alignment: .leading, spacing: 5) {
            if let remaining = snapshot.remainingMinor {
                Text("本月预算 \(formatMoney(snapshot.usedMinor)) / \(formatMoney(snapshot.usedMinor + remaining))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Text("本月预算")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color.primary.opacity(0.12))
                    Capsule()
                        .fill(fillColor)
                        .frame(width: proxy.size.width * progressRatio)
                }
            }
            .frame(height: 7)
        }
    }

    private var progressRatio: CGFloat {
        guard let total = snapshot.remainingMinor else { return 0 }
        let limit = snapshot.usedMinor + total
        guard limit > 0 else { return 0 }
        return CGFloat(min(1, max(0, Double(snapshot.usedMinor) / Double(limit))))
    }

    private var fillColor: Color {
        snapshot.isOverBudget ? .red : .teal
    }

    private var statBlocks: some View {
        HStack(spacing: 12) {
            statBlock(title: "本月已用", value: formatMoney(snapshot.usedMinor), tint: .secondary)
                .frame(maxWidth: .infinity)
            statBlock(
                title: "每日可用",
                value: snapshot.dailyMinor.map(formatMoney) ?? "—",
                tint: .teal
            )
            .frame(maxWidth: .infinity)
            statBlock(
                title: snapshot.isOverBudget ? "已超支" : "本月剩余",
                value: remainingValue,
                tint: snapshot.isOverBudget ? .red : .green
            )
            .frame(maxWidth: .infinity)
        }
    }

    private var remainingValue: String {
        guard let remaining = snapshot.remainingMinor else { return "—" }
        return formatMoney(snapshot.isOverBudget ? -remaining : remaining)
    }

    private func statBlock(title: String, value: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(size: 18, weight: .semibold, design: .rounded))
                .foregroundStyle(tint)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
        }
    }
}

// MARK: - 中尺寸

private struct MediumWidgetView: View {
    let snapshot: FinanceOSWidgetSnapshot
    let entry: FinanceOSWidgetEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text("本月已用概览")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(SmallWidgetView.updatedText(entry.date))
                    .font(.caption2)
                    .foregroundStyle(.secondary.opacity(0.8))
            }
            Spacer(minLength: 2)
            HStack(spacing: 12) {
                statBlock(title: "本月已用", value: formatMoney(snapshot.usedMinor), tint: .secondary)
                Divider().frame(height: 34)
                statBlock(title: "每日可用", value: snapshot.dailyMinor.map(formatMoney) ?? "—", tint: .teal)
                Divider().frame(height: 34)
                statBlock(
                    title: snapshot.isOverBudget ? "已超支" : "本月剩余",
                    value: remainingValue,
                    tint: snapshot.isOverBudget ? .red : .green
                )
            }
            .frame(maxWidth: .infinity)
            Spacer(minLength: 2)
            if !snapshot.hasBudget {
                Text("设置月预算后显示每日可用与剩余预算")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var remainingValue: String {
        guard let remaining = snapshot.remainingMinor else { return "—" }
        return formatMoney(snapshot.isOverBudget ? -remaining : remaining)
    }

    private func statBlock(title: String, value: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(size: 21, weight: .bold, design: .rounded))
                .foregroundStyle(tint)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - 空状态

private struct EmptyWidgetView: View {
    let message: String

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "tray")
                .font(.system(size: 22))
                .foregroundStyle(.secondary)
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(8)
    }
}

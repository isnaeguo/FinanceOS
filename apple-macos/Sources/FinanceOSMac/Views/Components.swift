import SwiftUI
import FinanceOSCore

// MARK: - 分类视觉映射

/// 将跨平台 iconKey 映射为 macOS 原生 SF Symbol 与主色。
struct CategoryVisual {
    let symbol: String
    let color: Color

    static func resolved(for category: FinanceOSCore.Category?) -> CategoryVisual {
        guard let category else {
            return CategoryVisual(symbol: "tag.slash.fill", color: .gray)
        }
        return resolved(iconKey: category.iconKey, type: category.type)
    }

    static func resolved(iconKey: String, type: CategoryType) -> CategoryVisual {
        switch iconKey {
        case "food": .init(symbol: "fork.knife", color: .orange)
        case "transport": .init(symbol: "tram.fill", color: .blue)
        case "shopping": .init(symbol: "bag.fill", color: .pink)
        case "entertainment": .init(symbol: "party.popper.fill", color: .purple)
        case "digital": .init(symbol: "desktopcomputer", color: .indigo)
        case "learning": .init(symbol: "book.fill", color: .teal)
        case "travel": .init(symbol: "airplane", color: .cyan)
        case "daily-needs": .init(symbol: "basket.fill", color: .green)
        case "income": .init(symbol: "banknote.fill", color: .mint)
        case "other": .init(symbol: "ellipsis.circle.fill", color: .gray)
        default:
            switch type {
            case .income: .init(symbol: "banknote.fill", color: .mint)
            case .expense: .init(symbol: "tag.fill", color: .orange)
            case .common: .init(symbol: "square.grid.2x2.fill", color: .gray)
            }
        }
    }
}

/// 可供用户自定义分类挑选的语义图标键。
let selectableIconKeys = [
    "food", "transport", "shopping", "entertainment", "digital",
    "learning", "travel", "daily-needs", "income", "other",
]

// MARK: - 日期格式

enum FinanceFormat {
    static func monthLabel(_ month: BudgetMonth) -> String {
        "\(month.year)年\(month.month)月"
    }

    static func dayLabel(_ date: Date, calendar: Calendar = .current) -> String {
        let components = calendar.dateComponents([.month, .day, .weekday], from: date)
        let weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"]
        let weekday = weekdays[(components.weekday ?? 1) - 1]
        return "\(components.month ?? 0)月\(components.day ?? 0)日 \(weekday)"
    }

    static func timeLabel(_ date: Date, calendar: Calendar = .current) -> String {
        let components = calendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", components.hour ?? 0, components.minute ?? 0)
    }

    static func monthTrendLabel(_ key: String) -> String {
        // key 形如 "2026-09"
        let parts = key.split(separator: "-")
        guard parts.count == 2, let month = Int(parts[1]) else { return key }
        return "\(month)月"
    }

    static func dayTrendLabel(_ key: String) -> String {
        // key 形如 "09-02"
        let parts = key.split(separator: "-")
        guard parts.count == 2, let month = Int(parts[0]), let day = Int(parts[1]) else { return key }
        return "\(month)/\(day)"
    }

    /// 图表纵轴使用“元”为单位。
    static func chartAmount(_ minor: Int64) -> Double {
        Double(minor) / 100
    }
}

// MARK: - 氛围背景

/// 为 Liquid Glass 提供折射来源的柔和光斑背景。
struct AuroraBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            (colorScheme == .dark ? Color.black : Color(white: 0.96)).ignoresSafeArea()
            GeometryReader { proxy in
                let size = proxy.size
                blob(color: .blue.opacity(colorScheme == .dark ? 0.28 : 0.30), size: size.width * 0.7)
                    .position(x: size.width * 0.18, y: size.height * 0.12)
                blob(color: .purple.opacity(colorScheme == .dark ? 0.24 : 0.26), size: size.width * 0.62)
                    .position(x: size.width * 0.88, y: size.height * 0.30)
                blob(color: .teal.opacity(colorScheme == .dark ? 0.20 : 0.24), size: size.width * 0.55)
                    .position(x: size.width * 0.55, y: size.height * 0.95)
                blob(color: .orange.opacity(colorScheme == .dark ? 0.16 : 0.18), size: size.width * 0.45)
                    .position(x: size.width * 0.10, y: size.height * 0.82)
            }
        }
    }

    private func blob(color: Color, size: CGFloat) -> some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .blur(radius: size * 0.28)
    }
}

// MARK: - 玻璃卡片

/// Liquid Glass 卡片容器：圆角玻璃底 + 内容。
struct GlassCard<Content: View>: View {
    var cornerRadius: CGFloat = 22
    var padding: CGFloat = 18
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
    }
}

/// 数值统计卡片。
struct StatCard: View {
    let title: String
    let value: String
    let symbol: String
    let tint: Color
    var footnote: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: symbol)
                    .foregroundStyle(tint)
                Text(title)
                    .foregroundStyle(.secondary)
            }
            .font(.callout)
            Text(value)
                .font(.system(size: 26, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            if let footnote {
                Text(footnote)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }
}

// MARK: - 分类图标

struct CategoryIconView: View {
    let category: FinanceOSCore.Category?
    var size: CGFloat = 32

    var body: some View {
        let visual = CategoryVisual.resolved(for: category)
        Image(systemName: visual.symbol)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(visual.color)
            .frame(width: size, height: size)
            .background(visual.color.opacity(0.18), in: Circle())
            .overlay(Circle().strokeBorder(visual.color.opacity(0.25), lineWidth: 0.5))
    }
}

// MARK: - 进度条

struct BudgetProgressBar: View {
    let ratio: Double?
    let isOver: Bool
    var height: CGFloat = 7

    var body: some View {
        let clamped = min(1, ratio ?? 0)
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(.quaternary)
                Capsule()
                    .fill(fillColor)
                    .frame(width: max(0, proxy.size.width * clamped))
            }
        }
        .frame(height: height)
    }

    private var fillColor: Color {
        guard let ratio, ratio > 0 else { return .secondary }
        if isOver { return .red }
        if ratio > 0.85 { return .orange }
        return .accentColor
    }
}

// MARK: - 空状态

struct EmptyStateView: View {
    let symbol: String
    let title: String
    var message: String? = nil

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.secondary)
                .symbolRenderingMode(.hierarchical)
            Text(title)
                .font(.headline)
            if let message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }
}

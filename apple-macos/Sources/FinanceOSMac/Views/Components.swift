import SwiftUI
import AppKit

enum WorkspacePalette {
    static let background = adaptive(0xf2f4f6, 0x0c0e11)
    static let panel = adaptive(0xffffff, 0x15181d)
    static let raised = adaptive(0xe9eef3, 0x1d232b)
    static let border = adaptive(0xcdd4dc, 0x343c47)
    static let accent = adaptive(0x254a70, 0xb9d2eb)
    static let secondary = adaptive(0x4c5a69, 0xaebac7)
    static let negative = adaptive(0x9f4226, 0xffae91)

    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            let hex = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat((hex >> 16) & 255) / 255,
                           green: CGFloat((hex >> 8) & 255) / 255,
                           blue: CGFloat(hex & 255) / 255, alpha: 1)
        })
    }
}

enum FinanceMotion {
    static let short = Animation.easeOut(duration: 0.18)
    static let reveal = AnyTransition.asymmetric(
        insertion: .opacity.combined(with: .offset(y: 5)), removal: .identity
    )
}

enum FinanceGlyph: Shape {
    case inbox, accounts, forecast, books, chat

    func path(in rect: CGRect) -> Path {
        let side = min(rect.width, rect.height) / 24
        let origin = CGPoint(x: rect.midX - 12 * side, y: rect.midY - 12 * side)
        var path = Path()
        func line(_ points: [CGPoint], closed: Bool = false) {
            guard let first = points.first else { return }
            func point(_ p: CGPoint) -> CGPoint { CGPoint(x: origin.x + p.x * side, y: origin.y + p.y * side) }
            path.move(to: point(first))
            for next in points.dropFirst() { path.addLine(to: point(next)) }
            if closed { path.closeSubpath() }
        }
        switch self {
        case .inbox:
            line([.init(x: 3, y: 10), .init(x: 7, y: 4), .init(x: 17, y: 4), .init(x: 21, y: 10), .init(x: 21, y: 20), .init(x: 3, y: 20)], closed: true)
            line([.init(x: 3, y: 11), .init(x: 8, y: 11), .init(x: 10, y: 15), .init(x: 14, y: 15), .init(x: 16, y: 11), .init(x: 21, y: 11)])
        case .accounts:
            line([.init(x: 12, y: 3), .init(x: 21, y: 8), .init(x: 12, y: 13), .init(x: 3, y: 8)], closed: true)
            line([.init(x: 3, y: 13), .init(x: 12, y: 18), .init(x: 21, y: 13)])
            line([.init(x: 3, y: 17), .init(x: 12, y: 22), .init(x: 21, y: 17)])
        case .forecast:
            line([.init(x: 3, y: 19), .init(x: 21, y: 19)])
            line([.init(x: 3, y: 14), .init(x: 8, y: 9), .init(x: 12, y: 12), .init(x: 20, y: 4)])
            line([.init(x: 15, y: 4), .init(x: 20, y: 4), .init(x: 20, y: 9)])
        case .books:
            line([.init(x: 12, y: 3), .init(x: 21, y: 8), .init(x: 21, y: 16), .init(x: 12, y: 21), .init(x: 3, y: 16), .init(x: 3, y: 8)], closed: true)
            line([.init(x: 3, y: 8), .init(x: 12, y: 13), .init(x: 21, y: 8)])
            line([.init(x: 12, y: 13), .init(x: 12, y: 21)])
        case .chat:
            line([.init(x: 12, y: 3), .init(x: 21, y: 8), .init(x: 21, y: 15), .init(x: 12, y: 20), .init(x: 8, y: 22), .init(x: 8, y: 18), .init(x: 3, y: 15), .init(x: 3, y: 8)], closed: true)
            line([.init(x: 8, y: 12), .init(x: 16, y: 12)])
        }
        return path
    }
}

struct FinanceLoading: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var label = "正在加载"
    var compact = false

    var body: some View {
        HStack(spacing: 11) {
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion)) { timeline in
                Canvas { context, size in
                    let unit = size.width / 21
                    let time = timeline.date.timeIntervalSinceReferenceDate
                    for index in 0..<3 {
                        let x = CGFloat(index * 6) * unit
                        let alpha = reduceMotion ? 0.7 : 0.45 + 0.5 * (0.5 + 0.5 * sin(time * 5 - Double(index)))
                        var shape = Path()
                        shape.move(to: CGPoint(x: x, y: 11 * unit))
                        shape.addLine(to: CGPoint(x: x + 4 * unit, y: 2 * unit))
                        shape.addLine(to: CGPoint(x: x + 8 * unit, y: 2 * unit))
                        shape.addLine(to: CGPoint(x: x + 4 * unit, y: 11 * unit))
                        shape.closeSubpath()
                        context.fill(shape, with: .color(WorkspacePalette.accent.opacity(alpha)))
                    }
                }
            }
            .frame(width: compact ? 20 : 30, height: compact ? 13 : 19)
            .accessibilityHidden(true)
            if !compact { Text(label).font(.callout).foregroundStyle(WorkspacePalette.secondary) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }
}

// MARK: - 分类视觉映射

/// 将跨平台 iconKey 映射为 macOS 原生 SF Symbol 与主色。
struct CategoryVisual {
    let symbol: String
    let color: Color

    static func resolved(for category: Category?) -> CategoryVisual {
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

// MARK: - 内容区

struct FinancePanel<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WorkspacePalette.panel, in: .rect(cornerRadius: 8))
            .overlay {
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(WorkspacePalette.border, lineWidth: 1)
                    .allowsHitTesting(false)
            }
    }
}

struct MonthNavigation: View {
    @Binding var selection: BudgetMonth
    let latest: BudgetMonth

    var body: some View {
        HStack(spacing: 8) {
            Button("上个月", systemImage: "chevron.left") { selection = selection.previous() }
                .labelStyle(.iconOnly)
            Text(FinanceFormat.monthLabel(selection))
                .font(.headline.monospacedDigit())
                .frame(minWidth: 100)
            Button("下个月", systemImage: "chevron.right") { selection = selection.next() }
                .labelStyle(.iconOnly)
                .disabled(selection >= latest)
            Spacer()
            if selection != .current {
                Button("本月") { selection = .current }
            }
        }
        .buttonStyle(.bordered)
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
    let category: Category?
    var size: CGFloat = 32

    var body: some View {
        let visual = CategoryVisual.resolved(for: category)
        Image(systemName: visual.symbol)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(visual.color)
            .frame(width: size, height: size)
            .background(visual.color.opacity(0.10), in: .rect(cornerRadius: 7))
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

import SwiftUI

// MARK: - 通用格式化

enum FMT {
    static func monthLabel(_ month: BudgetMonth) -> String {
        "\(month.year)年\(month.month)月"
    }

    static func dayLabel(_ date: Date) -> String {
        let components = Calendar.current.dateComponents([.month, .day, .weekday], from: date)
        let weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"]
        let weekday = weekdays[(components.weekday ?? 1) - 1]
        return "\(components.month ?? 0)月\(components.day ?? 0)日 \(weekday)"
    }

    static func timeLabel(_ date: Date) -> String {
        let components = Calendar.current.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", components.hour ?? 0, components.minute ?? 0)
    }
}

extension BudgetMonth {
    static var current: BudgetMonth {
        let calendar = Calendar.current
        return BudgetMonth(
            year: calendar.component(.year, from: Date()),
            month: calendar.component(.month, from: Date())
        )
    }
}


#if os(iOS)
import UIKit
#endif

extension Color {
    /// 页面底色
    static var fosPage: Color {
        #if os(iOS)
        Color(UIColor.systemGroupedBackground)
        #else
        Color(white: 0.94)
        #endif
    }

    /// 卡片底色
    static var fosCard: Color {
        #if os(iOS)
        Color(UIColor.secondarySystemGroupedBackground)
        #else
        Color(white: 0.99)
        #endif
    }

    /// 内嵌输入/未选底色
    static var fosField: Color {
        #if os(iOS)
        Color(UIColor.tertiarySystemGroupedBackground)
        #else
        Color(white: 0.92)
        #endif
    }

    /// 细填充
    static var fosFill: Color {
        #if os(iOS)
        Color(UIColor.systemFill)
        #else
        Color(white: 0.85)
        #endif
    }
}

// MARK: - 卡片与进度

struct SectionCard<Content: View>: View {
    var padding: CGFloat = 14
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.fosCard)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

struct MiniProgressBar: View {
    let ratio: Double?
    let isOver: Bool

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.fosFill)
                if let ratio {
                    Capsule()
                        .fill(isOver ? Color.red : (ratio > 0.85 ? Color.orange : Color.accentColor))
                        .frame(width: max(0, min(1, ratio)) * proxy.size.width)
                }
            }
        }
        .frame(height: 7)
    }
}

// MARK: - 分类图标（跨端语义键 → SF Symbols）

struct CategoryIconView: View {
    let category: Category?
    var size: CGFloat = 30

    var body: some View {
        let symbol = categorySymbol(category)
        Image(systemName: symbol.name)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(symbol.color)
            .frame(width: size, height: size)
            .background(symbol.color.opacity(0.18), in: Circle())
    }

    private func categorySymbol(_ category: Category?) -> (name: String, color: Color) {
        let key = category?.iconKey ?? ""
        switch key {
        case "food": return ("fork.knife", .orange)
        case "transport": return ("tram.fill", .blue)
        case "shopping": return ("bag.fill", .pink)
        case "entertainment": return ("party.popper.fill", .purple)
        case "digital": return ("desktopcomputer", .indigo)
        case "learning": return ("book.fill", .teal)
        case "travel": return ("airplane", .cyan)
        case "daily-needs": return ("basket.fill", .green)
        case "income": return ("banknote.fill", .mint)
        default: return category?.type == .income ? ("banknote.fill", .mint) : ("tag.fill", .gray)
        }
    }
}

// MARK: - 月份切换

struct MonthSwitcher: View {
    let month: BudgetMonth
    let canGoNext: Bool
    let onPrevious: () -> Void
    let onNext: () -> Void
    var onBackToCurrent: (() -> Void)? = nil

    var body: some View {
        HStack {
            Button(action: onPrevious) {
                Image(systemName: "chevron.left")
                    .font(.body.weight(.semibold))
                    .frame(width: 36, height: 36)
                    .background(Color.fosCard)
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)

            Spacer()
            VStack(spacing: 2) {
                Text(FMT.monthLabel(month))
                    .font(.headline.monospacedDigit())
                if let onBackToCurrent, month != BudgetMonth.current {
                    Button("回到本月", action: onBackToCurrent)
                        .font(.caption)
                }
            }
            Spacer()

            Button(action: onNext) {
                Image(systemName: "chevron.right")
                    .font(.body.weight(.semibold))
                    .frame(width: 36, height: 36)
                    .background(Color.fosCard)
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .disabled(!canGoNext)
            .opacity(canGoNext ? 1 : 0.3)
        }
    }
}

// MARK: - 空状态

struct EmptyHintView: View {
    let symbol: String
    let title: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: symbol)
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text(title)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
    }
}


func fosTrailingPlacement() -> ToolbarItemPlacement {
#if os(iOS)
    return .topBarTrailing
#else
    return .primaryAction
#endif
}

func fosLeadingPlacement() -> ToolbarItemPlacement {
#if os(iOS)
    return .topBarLeading
#else
    return .navigation
#endif
}

import SwiftUI
import Charts

// MARK: - 总览（可切换历史月份）

struct HomeTab: View {
    @Environment(FinanceStore.self) private var store

    @State private var month: BudgetMonth = BudgetMonth.current
    @State private var showAdd = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    MonthSwitcher(
                        month: month,
                        canGoNext: month < BudgetMonth.current,
                        onPrevious: { month = month.previous() },
                        onNext: { month = month.next() },
                        onBackToCurrent: { month = BudgetMonth.current }
                    )

                    summaryCards
                    budgetCard
                    if month == BudgetMonth.current {
                        dailyCard
                    }
                    monthlyTrend
                    dailyTrendCard
                    categoryRankingCard
                    recentTransactions
                }
                .padding(.horizontal, 16)
            }
            .background(Color.fosPage.ignoresSafeArea())
            .navigationTitle("总览")
            .toolbar {
                                    ToolbarItem(placement: fosTrailingPlacement()) {
                    Button { showAdd = true } label: {
                        Image(systemName: "plus.circle.fill")
                    }
                }
            }
            .sheet(isPresented: $showAdd) {
                AddTransactionSheetContent(store: store)
            }
        }
    }

    private var summary: MonthlySummary { store.monthlySummary(in: month.period()) }

    private var summaryCards: some View {
        HStack(spacing: 12) {
            stat("收入", formatMoney(summary.totalIncome), .mint)
            stat("支出", formatMoney(summary.totalExpense), .red)
            stat("结余", formatMoney(summary.netChange), summary.netChange >= 0 ? .green : .orange)
        }
    }

    private func stat(_ title: String, _ value: String, _ color: Color) -> some View {
        SectionCard {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Text(value)
                    .font(.system(.title3, design: .rounded, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(color)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var budgetCard: some View {
        let status = store.budgetStatus(in: month.period())
        return SectionCard {
            VStack(alignment: .leading, spacing: 10) {
                Label("月总预算", systemImage: "target")
                    .font(.headline)
                if status.total.hasBudget {
                    Text("\(formatMoney(max(0, status.total.amountUsed))) / \(formatMoney(status.total.amountLimit ?? 0))")
                        .font(.title3.weight(.semibold).monospacedDigit())
                    MiniProgressBar(ratio: status.total.usageRatio, isOver: status.total.isOverBudget)
                    Text(remainingText(status.total))
                        .font(.caption)
                        .foregroundStyle(status.total.isOverBudget ? Color.red : Color.secondary)
                } else {
                    Text("未设置本月总预算")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func remainingText(_ usage: BudgetUsage) -> String {
        guard let remaining = usage.amountRemaining else { return "" }
        if usage.isOverBudget { return "已超支 \(formatMoney(-remaining))" }
        if let ratio = usage.usageRatio {
            if ratio <= 0 { return "本月有结余，剩余 \(formatMoney(remaining))" }
            return String(format: "已使用 %.0f%%，剩余 %@", ratio * 100, formatMoney(remaining))
        }
        return "剩余 \(formatMoney(remaining))"
    }

    private var dailyCard: some View {
        let daily = store.dailyAvailableBudget()
        return Group {
            if let daily {
                SectionCard {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("今日可用预算", systemImage: "sun.max.fill")
                            .font(.headline)
                            .symbolRenderingMode(.multicolor)
                        Text(formatMoney(daily.dailyAmount))
                            .font(.system(size: 30, weight: .bold, design: .rounded))
                            .monospacedDigit()
                            .foregroundStyle(daily.isOverBudget ? Color.red : Color.primary)
                        Text(daily.isOverBudget
                            ? "已超支 \(formatMoney(-daily.amountRemaining))，剩余 \(daily.remainingDays) 天"
                            : "本月还剩 \(formatMoney(daily.amountRemaining)) · 剩余 \(daily.remainingDays) 天")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private var monthlyTrend: some View {
        let points = store.monthlyExpenseTrend(anchorMonth: month, count: 6)
        let maxAmount = points.map(\.amount).max() ?? 1
        return SectionCard {
            VStack(alignment: .leading, spacing: 10) {
                Label("近 6 个月支出", systemImage: "chart.bar.fill")
                    .font(.headline)
                if points.contains(where: { $0.amount > 0 }) {
                    HStack(alignment: .bottom, spacing: 6) {
                        ForEach(points) { point in
                            VStack(spacing: 4) {
                                Text(shortAmount(point.amount))
                                    .font(.system(size: 9))
                                    .foregroundStyle(.secondary)
                                RoundedRectangle(cornerRadius: 3)
                                    .fill(point.amount == points.last?.amount ? Color.teal : Color.accentColor.opacity(0.75))
                                    .frame(height: point.amount == 0 ? 2 : max(6, CGFloat(point.amount) / CGFloat(maxAmount) * 70))
                                Text(shortMonthLabel(point.key))
                                    .font(.system(size: 9))
                                    .foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity)
                        }
                    }
                } else {
                    EmptyHintView(symbol: "chart.bar", title: "暂无支出数据")
                }
            }
        }
    }

    private func shortMonthLabel(_ key: String) -> String {
        let parts = key.split(separator: "-")
        if parts.count == 2, let m = Int(parts[1]) { return "\(m)月" }
        return key
    }

    private func shortAmount(_ minor: Int64) -> String {
        String(format: "%.0f", Double(minor) / 100)
    }

    private enum TrendRange: Int, CaseIterable, Identifiable {
        case seven = 7
        case thirty = 30
        var id: Int { rawValue }
        var label: String { "近 \(rawValue) 天" }
    }

    @State private var trendRange: TrendRange = .seven

    private var dailyTrendCard: some View {
        let data = store.dailyExpenseTrendData(days: trendRange.rawValue)
        let hasData = data.contains { $0.amount > 0 }
        return SectionCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label("每日消费趋势", systemImage: "chart.xyaxis.line").font(.headline)
                    Spacer()
                    Picker("范围", selection: $trendRange) {
                        ForEach(TrendRange.allCases) { range in
                            Text(range.label).tag(range)
                        }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 170)
                }
                if hasData {
                    Chart(data) { datum in
                        AreaMark(
                            x: .value("日期", datum.date),
                            y: .value("支出", Double(datum.amount) / 100)
                        )
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(
                            .linearGradient(colors: [Color.teal.opacity(0.35), Color.teal.opacity(0.02)],
                                            startPoint: .top, endPoint: .bottom)
                        )
                        LineMark(
                            x: .value("日期", datum.date),
                            y: .value("支出", Double(datum.amount) / 100)
                        )
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(.teal)
                        .lineStyle(StrokeStyle(lineWidth: 2))
                    }
                    .chartXAxis {
                        AxisMarks(values: .automatic) { _ in
                            AxisValueLabel(format: .dateTime.month(.twoDigits).day(.twoDigits))
                        }
                    }
                    .frame(height: 150)
                } else {
                    EmptyHintView(symbol: "chart.xyaxis.line", title: "暂无支出数据")
                }
            }
        }
    }

    private var categoryRankingCard: some View {
        let ranking = summary.categoryRanking
        guard !ranking.isEmpty else {
            return AnyView(EmptyView())
        }
        let maxAmount = ranking.first?.amount ?? 1
        return AnyView(
            SectionCard {
                VStack(alignment: .leading, spacing: 12) {
                    Label("分类消费排行", systemImage: "list.number").font(.headline)
                    ForEach(ranking.prefix(8), id: \.categoryId) { entry in
                        let category = store.category(id: entry.categoryId)
                        HStack(spacing: 10) {
                            CategoryIconView(category: category, size: 26)
                            Text(category?.name ?? "未知")
                                .font(.callout)
                                .frame(width: 74, alignment: .leading)
                                .lineLimit(1)
                            GeometryReader { proxy in
                                ZStack(alignment: .leading) {
                                    Capsule().fill(Color.fosFill)
                                    Capsule()
                                        .fill(Color.accentColor)
                                        .frame(width: max(0, min(1, Double(entry.amount) / Double(maxAmount))) * proxy.size.width)
                                }
                            }
                            .frame(height: 6)
                            Text(formatMoney(entry.amount))
                                .font(.callout.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .frame(minWidth: 72, alignment: .trailing)
                        }
                    }
                }
            }
        )
    }

    private var recentTransactions: some View {
        let recent = store.recentTransactions(limit: 6)
        return SectionCard {
            VStack(alignment: .leading, spacing: 8) {
                Label("最近流水", systemImage: "clock")
                    .font(.headline)
                if recent.isEmpty {
                    EmptyHintView(symbol: "tray", title: "还没有流水")
                } else {
                    ForEach(recent) { transaction in
                        TransactionCell(transaction: transaction, category: store.category(id: transaction.categoryId))
                        if transaction.id != recent.last?.id {
                            Divider()
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 通用流水行

struct TransactionCell: View {
    let transaction: Transaction
    let category: Category?
    var showsTime = true

    var body: some View {
        HStack(spacing: 10) {
            CategoryIconView(category: category, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(transaction.note?.isEmpty == false ? transaction.note! : (category?.name ?? "未分类"))
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Text(amountText)
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(transaction.type == .income ? Color.mint : Color.primary)
        }
    }

    private var subtitle: String {
        var parts: [String] = []
        if let account = transaction.accountId, !account.isEmpty { parts.append(account) }
        if showsTime { parts.append(FMT.timeLabel(transaction.dateTime)) }
        return parts.joined(separator: " · ")
    }

    private var amountText: String {
        transaction.type == .income ? "+\(formatMoney(transaction.amount))" : "-\(formatMoney(transaction.amount))"
    }
}

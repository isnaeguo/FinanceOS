import SwiftUI
import Charts

struct DashboardView: View {
    @Environment(FinanceStore.self) private var store

    @State private var selectedMonth: BudgetMonth = .current
    @State private var spendingRange: SpendingRange = .sevenDays

    enum SpendingRange: Int, CaseIterable, Identifiable {
        case sevenDays = 7
        case thirtyDays = 30

        var id: Int { rawValue }
        var label: String { "近 \(rawValue) 天" }
    }

    var body: some View {
        let period = selectedMonth.period()
        let summary = store.monthlySummary(in: period)
        let status = store.budgetStatus(in: period)

        ScrollView {
            VStack(spacing: 16) {
                monthHeader
                statRow(summary)
                budgetRow(summary: summary, status: status)
                monthlyTrendCard
                dailyTrendCard
                if !summary.categoryRanking.isEmpty {
                    rankingCard(summary)
                }
                recentCard
            }
            .padding(20)
        }
        .background(AuroraBackground())
        .navigationTitle("总览")
        .onAppear {
            let current = BudgetMonth.current
            if selectedMonth > current { selectedMonth = current }
        }
    }

    // MARK: - 月份导航

    private var monthHeader: some View {
        let current = BudgetMonth.current
        return HStack {
            GlassEffectContainer(spacing: 10) {
                HStack(spacing: 10) {
                    Button {
                        selectedMonth = selectedMonth.previous()
                    } label: {
                        Image(systemName: "chevron.left")
                    }
                    .buttonStyle(.glass)
                    .disabled(false)

                    Text(FinanceFormat.monthLabel(selectedMonth))
                        .font(.title3.weight(.semibold))
                        .monospacedDigit()
                        .frame(minWidth: 110)

                    Button {
                        selectedMonth = selectedMonth.next()
                    } label: {
                        Image(systemName: "chevron.right")
                    }
                    .buttonStyle(.glass)
                    .disabled(selectedMonth >= current)
                }
            }

            Spacer()

            if selectedMonth != current {
                Button("回到本月") {
                    selectedMonth = current
                }
                .buttonStyle(.glass)
            }
        }
    }

    // MARK: - 收支概览

    private func statRow(_ summary: MonthlySummary) -> some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 16) {
            GlassCard {
                StatCard(
                    title: "收入",
                    value: formatMoney(summary.totalIncome),
                    symbol: "arrow.down.left",
                    tint: .mint
                )
            }
            GlassCard {
                StatCard(
                    title: "支出",
                    value: formatMoney(summary.totalExpense),
                    symbol: "arrow.up.right",
                    tint: .red
                )
            }
            GlassCard {
                StatCard(
                    title: "结余",
                    value: formatMoney(summary.netChange),
                    symbol: "equal.circle.fill",
                    tint: summary.netChange >= 0 ? .green : .orange
                )
            }
        }
    }

    // MARK: - 预算

    private func budgetRow(summary: MonthlySummary, status: MonthlyBudgetStatus) -> some View {
        let daily = store.dailyAvailableBudget()
        let isCurrentMonth = selectedMonth == BudgetMonth.current
        let total = status.total

        return LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 16) {
            GlassCard {
                if isCurrentMonth, let daily {
                    DailyBudgetContent(daily: daily)
                } else if total.hasBudget {
                    TotalBudgetContent(usage: total)
                } else {
                    EmptyBudgetHint()
                }
            }
            GlassCard {
                if total.hasBudget {
                    TotalBudgetContent(usage: total)
                } else {
                    EmptyBudgetHint()
                }
            }
        }
    }

    // MARK: - 月度趋势

    private var monthlyTrendCard: some View {
        let points = store.monthlyExpenseTrend(anchorMonth: selectedMonth, count: 6)
        return GlassCard {
            VStack(alignment: .leading, spacing: 14) {
                Label("近 6 个月支出趋势", systemImage: "chart.bar.fill")
                    .font(.headline)
                if points.contains(where: { $0.amount > 0 }) {
                    Chart(points) { point in
                        BarMark(
                            x: .value("月份", FinanceFormat.monthTrendLabel(point.key)),
                            y: .value("支出", FinanceFormat.chartAmount(point.amount))
                        )
                        .foregroundStyle(
                            point.key == "\(selectedMonth.year)-\(String(format: "%02d", selectedMonth.month))"
                                ? AnyShapeStyle(.teal.gradient)
                                : AnyShapeStyle(.blue.opacity(0.75).gradient)
                        )
                        .cornerRadius(5)
                    }
                    .chartYAxis {
                        AxisMarks(position: .leading) { value in
                            AxisGridLine()
                            AxisValueLabel {
                                if let amount = value.as(Double.self) {
                                    Text(abbreviateAmount(amount))
                                }
                            }
                        }
                    }
                    .frame(height: 170)
                } else {
                    EmptyStateView(symbol: "chart.bar", title: "暂无支出数据", message: "记录几笔支出后即可看到趋势")
                }
            }
        }
    }

    // MARK: - 按日趋势

    private var dailyTrendCard: some View {
        let data = store.dailyExpenseTrendData(days: spendingRange.rawValue)
        let hasData = data.contains { $0.amount > 0 }
        return GlassCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Label("每日消费趋势", systemImage: "chart.xyaxis.line")
                        .font(.headline)
                    Spacer()
                    Picker("范围", selection: $spendingRange) {
                        ForEach(SpendingRange.allCases) { range in
                            Text(range.label).tag(range)
                        }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 190)
                }
                if hasData {
                    Chart(data) { datum in
                        AreaMark(
                            x: .value("日期", datum.date),
                            y: .value("支出", FinanceFormat.chartAmount(datum.amount))
                        )
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(.linearGradient(colors: [.teal.opacity(0.35), .teal.opacity(0.02)], startPoint: .top, endPoint: .bottom))

                        LineMark(
                            x: .value("日期", datum.date),
                            y: .value("支出", FinanceFormat.chartAmount(datum.amount))
                        )
                        .interpolationMethod(.catmullRom)
                        .foregroundStyle(.teal)
                        .lineStyle(StrokeStyle(lineWidth: 2.2, lineCap: .round))

                        PointMark(
                            x: .value("日期", datum.date),
                            y: .value("支出", FinanceFormat.chartAmount(datum.amount))
                        )
                        .symbolSize(22)
                        .foregroundStyle(.teal)
                    }
                    .chartXAxis {
                        AxisMarks(values: .automatic) { _ in
                            AxisGridLine()
                            AxisValueLabel(format: .dateTime.month(.twoDigits).day(.twoDigits), centered: false)
                        }
                    }
                    .chartYAxis {
                        AxisMarks(position: .leading) { value in
                            AxisGridLine()
                            AxisValueLabel {
                                if let amount = value.as(Double.self) {
                                    Text(abbreviateAmount(amount))
                                }
                            }
                        }
                    }
                    .frame(height: 160)
                } else {
                    EmptyStateView(symbol: "chart.xyaxis.line", title: "暂无支出数据")
                }
            }
        }
    }

    // MARK: - 分类排行

    private func rankingCard(_ summary: MonthlySummary) -> some View {
        let ranking = summary.categoryRanking
        let maxAmount = ranking.first?.amount ?? 0
        return GlassCard {
            VStack(alignment: .leading, spacing: 14) {
                Label("分类消费排行", systemImage: "list.number")
                    .font(.headline)
                VStack(spacing: 10) {
                    ForEach(ranking.prefix(8), id: \.categoryId) { entry in
                        let category = store.category(id: entry.categoryId)
                        HStack(spacing: 10) {
                            CategoryIconView(category: category, size: 28)
                            Text(category?.name ?? "未知分类")
                                .font(.callout)
                                .frame(width: 90, alignment: .leading)
                                .lineLimit(1)
                            BudgetProgressBar(
                                ratio: maxAmount > 0 ? Double(entry.amount) / Double(maxAmount) : 0,
                                isOver: false
                            )
                            Text(formatMoney(entry.amount))
                                .font(.callout.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .frame(minWidth: 84, alignment: .trailing)
                        }
                    }
                }
            }
        }
    }

    // MARK: - 最近流水

    private var recentCard: some View {
        let recent = store.recentTransactions(limit: 8)
        return GlassCard {
            VStack(alignment: .leading, spacing: 8) {
                Label("最近流水", systemImage: "clock")
                    .font(.headline)
                if recent.isEmpty {
                    EmptyStateView(symbol: "tray", title: "还没有流水", message: "点击工具栏的“记一笔”开始记录")
                } else {
                    ForEach(recent) { transaction in
                        TransactionRow(transaction: transaction, category: store.category(id: transaction.categoryId))
                        if transaction.id != recent.last?.id {
                            Divider().opacity(0.4)
                        }
                    }
                }
            }
        }
    }

    private func abbreviateAmount(_ major: Double) -> String {
        let formatter = abbreviateFormatter
        return formatter.string(from: NSNumber(value: major)) ?? String(major)
    }

    private let abbreviateFormatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 0
        formatter.currencySymbol = "¥"
        formatter.positivePrefix = "¥"
        return formatter
    }()
}

// MARK: - 每日可用预算内容

private struct DailyBudgetContent: View {
    let daily: DailyAvailableBudget

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("今日可用预算", systemImage: "sun.max.fill")
                .font(.callout)
                .foregroundStyle(.secondary)
                .symbolRenderingMode(.multicolor)
            Text(formatMoney(daily.dailyAmount))
                .font(.system(size: 30, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(daily.isOverBudget ? Color.red : Color.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(footnote)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var footnote: String {
        if daily.isOverBudget {
            return "本月预算已超支 \(formatMoney(-daily.amountRemaining))，剩余 \(daily.remainingDays) 天"
        }
        return "本月还剩 \(formatMoney(daily.amountRemaining)) · 剩余 \(daily.remainingDays) 天"
    }
}

private struct TotalBudgetContent: View {
    let usage: BudgetUsage

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("月总预算", systemImage: "target")
                .font(.callout)
                .foregroundStyle(.secondary)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(formatMoney(usage.amountUsed))
                    .font(.system(size: 26, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                Text("/ \(formatMoney(usage.amountLimit ?? 0))")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            Text(footnote)
                .font(.caption)
                .foregroundStyle(usage.isOverBudget ? Color.red : Color.secondary)
            BudgetProgressBar(ratio: usage.usageRatio, isOver: usage.isOverBudget)
        }
    }

    private var footnote: String {
        guard let remaining = usage.amountRemaining else { return "" }
        if usage.isOverBudget {
            return "已超支 \(formatMoney(-remaining))"
        }
        if let ratio = usage.usageRatio {
            return String(format: "已使用 %.0f%%，剩余 %@", ratio * 100, formatMoney(remaining))
        }
        return "剩余 \(formatMoney(remaining))"
    }
}

private struct EmptyBudgetHint: View {
    @Environment(AppRouter.self) private var router

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("月总预算", systemImage: "target")
                .font(.callout)
                .foregroundStyle(.secondary)
            Text("尚未设置预算")
                .font(.system(size: 22, weight: .semibold, design: .rounded))
            Button("去设置预算") {
                router.section = .budgets
            }
            .buttonStyle(.glass)
            .controlSize(.small)
        }
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

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
        let period = selectedMonth.period(calendar: store.calendar)
        let summary = store.monthlySummary(in: period)
        let status = store.budgetStatus(in: period, summary: summary)
        let monthEnd = store.calendar.date(byAdding: .day, value: -1, to: period.endExclusive) ?? Date()
        let trendDay = min(Date(), monthEnd)

        ScrollView {
            LazyVStack(spacing: 14) {
                MonthNavigation(selection: $selectedMonth, latest: .current)
                statRow(summary)
                budgetRow(status: status)

                ExpenseTrendPanel(
                    title: "近 6 个月净支出",
                    periods: SummaryCalculations.monthTrendPeriods(
                        anchorMonth: selectedMonth, count: 6, calendar: store.calendar
                    ),
                    style: .month
                ) { EmptyView() }
                ExpenseTrendPanel(
                    title: "每日净支出",
                    periods: SummaryCalculations.dailyTrendPeriods(
                        anchorDay: trendDay, days: spendingRange.rawValue, calendar: store.calendar
                    ),
                    style: .day
                ) {
                    Picker("范围", selection: $spendingRange) {
                        ForEach(SpendingRange.allCases) { range in
                            Text(range.label).tag(range)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .frame(width: 170)
                }
                if !summary.expensesByCategory.isEmpty { rankingCard(summary) }
                recentCard
            }
            .frame(maxWidth: 1100)
            .padding(20)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("总览")
    }

    private func statRow(_ summary: MonthlySummary) -> some View {
        FinancePanel {
            HStack(spacing: 18) {
                StatCard(title: "收入", value: formatMoney(summary.totalIncome), symbol: "arrow.down.left", tint: .green)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Divider()
                StatCard(title: "支出", value: formatMoney(summary.totalExpense), symbol: "arrow.up.right", tint: .secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Divider()
                StatCard(
                    title: "结余", value: formatMoney(summary.netChange), symbol: "equal",
                    tint: summary.netChange >= 0 ? .accentColor : .red
                )
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func budgetRow(status: MonthlyBudgetStatus) -> some View {
        let daily = selectedMonth == .current ? store.dailyAvailableBudget(calendar: store.calendar) : nil
        return HStack(alignment: .top, spacing: 14) {
            if let daily {
                FinancePanel { DailyBudgetContent(daily: daily) }
            }
            FinancePanel {
                if status.total.hasBudget { TotalBudgetContent(usage: status.total) }
                else { EmptyBudgetHint() }
            }
        }
    }

    // MARK: - 分类排行

    private func rankingCard(_ summary: MonthlySummary) -> some View {
        let ranking = summary.categoryRanking
        let maxAmount = ranking.first?.amount ?? 0
        return FinancePanel {
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
        return FinancePanel {
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

}

private struct ExpenseTrendPanel<Controls: View>: View {
    enum Style { case month, day }

    private struct Request: Equatable {
        let periods: [ExpenseTrendPeriod]
        let revision: UInt64
    }

    @Environment(FinanceStore.self) private var store
    let title: String
    let periods: [ExpenseTrendPeriod]
    let style: Style
    @ViewBuilder var controls: Controls
    @State private var loaded: Request?
    @State private var points: [ExpenseTrendPoint] = []

    var body: some View {
        let request = Request(periods: periods, revision: store.ledgerRevision)
        FinancePanel {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text(title).font(.headline)
                    Spacer(minLength: 10)
                    controls
                }
                Group {
                    if loaded != request {
                        FinanceLoading(compact: true)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else if points.contains(where: { $0.amount != 0 }) {
                        chart
                    } else {
                        Text("暂无净支出变化")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .frame(height: 150)
            }
        }
        .task(id: request) {
            let result = await store.loadExpenseTrend(periods: request.periods)
            guard !Task.isCancelled else { return }
            points = result
            loaded = request
        }
    }

    private var chart: some View {
        let step = max(1, (points.count + 5) / 6)
        let ticks = points.enumerated().compactMap { index, point in
            index.isMultiple(of: step) ? label(point.key) : nil
        }
        return Chart(points) { point in
            switch style {
            case .month:
                BarMark(
                    x: .value("月份", label(point.key)),
                    y: .value("净支出", FinanceFormat.chartAmount(point.amount))
                )
                .foregroundStyle(Color.accentColor.opacity(point.id == points.last?.id ? 1 : 0.45))
                .cornerRadius(3)
            case .day:
                LineMark(
                    x: .value("日期", label(point.key)),
                    y: .value("净支出", FinanceFormat.chartAmount(point.amount))
                )
                .foregroundStyle(Color.accentColor)
                .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            }
        }
        .chartXAxis { AxisMarks(values: ticks) { _ in AxisValueLabel() } }
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let amount = value.as(Double.self) {
                        Text(amount.formatted(.number.notation(.compactName)))
                    }
                }
            }
        }
        .accessibilityLabel(title)
    }

    private func label(_ key: String) -> String {
        style == .month ? FinanceFormat.monthTrendLabel(key) : FinanceFormat.dayTrendLabel(key)
    }
}

// MARK: - 每日可用预算内容

private struct DailyBudgetContent: View {
    let daily: DailyAvailableBudget

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("今日可用预算", systemImage: "sun.max.fill")
                .font(.callout)
                .foregroundStyle(.secondary)
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
                Text(formatMoney(max(0, usage.amountUsed)))
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
            if ratio <= 0 { return "本月有结余，剩余 \(formatMoney(remaining))" }
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
            .buttonStyle(.bordered)
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

import SwiftUI
import FinanceOSCore

struct BudgetView: View {
    @Environment(FinanceStore.self) private var store

    /// 预算可按需导航到任意月份；未来最远到紧邻的下个月，过去不限。
    @State private var selectedMonth: BudgetMonth = .current
    @State private var editing: BudgetEditContext?

    var body: some View {
        let period = selectedMonth.period()
        let status = store.budgetStatus(in: period)

        ScrollView {
            VStack(spacing: 16) {
                monthSwitcher
                totalBudgetCard(status: status)
                categoryBudgetsCard(status: status)
            }
            .padding(20)
        }
        .background(AuroraBackground())
        .navigationTitle("预算")
        .sheet(item: $editing) { context in
            BudgetEditSheet(store: store, context: context)
        }
    }

    private var monthSwitcher: some View {
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
                    .disabled(selectedMonth >= current.next())
                }
            }

            if selectedMonth != current {
                Button("回到本月") {
                    selectedMonth = current
                }
                .buttonStyle(.glass)
            }

            Spacer()

            if selectedMonth == current {
                Text("预算将按当月实际支出计算进度")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else if selectedMonth == current.next() {
                Text("下月预算创建后，本月视图不受影响")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - 总预算

    private func totalBudgetCard(status: MonthlyBudgetStatus) -> some View {
        let usage = status.total
        return GlassCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Label("月总预算", systemImage: "target")
                        .font(.headline)
                    Spacer()
                    Button {
                        editing = BudgetEditContext(
                            month: selectedMonth,
                            categoryId: nil,
                            categoryName: nil,
                            currentLimit: usage.amountLimit ?? 0
                        )
                    } label: {
                        Label(usage.hasBudget ? "调整" : "设置", systemImage: "slider.horizontal.3")
                    }
                    .buttonStyle(.glass)
                }

                if usage.hasBudget {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(formatMoney(usage.amountUsed))
                            .font(.system(size: 30, weight: .bold, design: .rounded))
                            .monospacedDigit()
                        Text("/ \(formatMoney(usage.amountLimit ?? 0))")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                        if usage.isOverBudget {
                            Text("已超支")
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(.red.opacity(0.18), in: Capsule())
                                .foregroundStyle(.red)
                        }
                    }
                    BudgetProgressBar(ratio: usage.usageRatio, isOver: usage.isOverBudget, height: 9)
                    if let remaining = usage.amountRemaining, !usage.isOverBudget {
                        Text("剩余 \(formatMoney(remaining))")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Text("尚未设置本月总预算")
                        .font(.title3.weight(.medium))
                        .foregroundStyle(.secondary)
                    Text("设置后，总览将显示每日可用预算和整体使用进度。")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    // MARK: - 分类预算

    private func categoryBudgetsCard(status: MonthlyBudgetStatus) -> some View {
        let budgetable = store.categories.filter { $0.accepts(.expense) }
        let settled = budgetable.filter { status.categories[$0.id] != nil }
        let unset = budgetable.filter { status.categories[$0.id] == nil }
        let summary = store.monthlySummary(in: selectedMonth.period())

        return GlassCard {
            VStack(alignment: .leading, spacing: 14) {
                Label("分类预算", systemImage: "square.stack.3d.up.fill")
                    .font(.headline)

                if settled.isEmpty {
                    Text("还没有分类预算，可为高频支出分类单独设置额度。")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(settled) { category in
                        CategoryBudgetRow(
                            category: category,
                            usage: status.categories[category.id],
                            monthExpense: summary.expensesByCategory[category.id] ?? 0
                        ) {
                            editing = BudgetEditContext(
                                month: selectedMonth,
                                categoryId: category.id,
                                categoryName: category.name,
                                currentLimit: status.categories[category.id]?.amountLimit ?? 0
                            )
                        }
                        if category.id != settled.last?.id {
                            Divider().opacity(0.4)
                        }
                    }
                }

                if !unset.isEmpty {
                    Divider().opacity(0.4)
                    Menu {
                        ForEach(unset) { category in
                            Button {
                                editing = BudgetEditContext(
                                    month: selectedMonth,
                                    categoryId: category.id,
                                    categoryName: category.name,
                                    currentLimit: 0
                                )
                            } label: {
                                Label(category.name, systemImage: CategoryVisual.resolved(for: category).symbol)
                            }
                        }
                    } label: {
                        Label("为分类设置预算", systemImage: "plus")
                    }
                }
            }
        }
    }
}

private struct CategoryBudgetRow: View {
    let category: FinanceOSCore.Category
    let usage: BudgetUsage?
    let monthExpense: Int64
    let onEdit: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            CategoryIconView(category: category, size: 32)
            VStack(alignment: .leading, spacing: 4) {
                Text(category.name)
                    .font(.callout.weight(.medium))
                if let usage {
                    HStack(spacing: 6) {
                        Text("\(formatMoney(usage.amountUsed)) / \(formatMoney(usage.amountLimit ?? 0))")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                        if usage.isOverBudget {
                            Text("超支")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.red)
                        }
                    }
                } else {
                    Text("本月支出 \(formatMoney(monthExpense))")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if let usage {
                BudgetProgressBar(ratio: usage.usageRatio, isOver: usage.isOverBudget)
                    .frame(width: 130)
            }
            Button("编辑") { onEdit() }
                .buttonStyle(.glass)
                .controlSize(.small)
        }
        .padding(.vertical, 2)
    }
}

// MARK: - 预算编辑

struct BudgetEditContext: Identifiable {
    let id = UUID()
    let month: BudgetMonth
    /// `nil` 表示总预算。
    let categoryId: String?
    let categoryName: String?
    let currentLimit: Int64
}

struct BudgetEditSheet: View {
    @Environment(\.dismiss) private var dismiss
    let store: FinanceStore
    let context: BudgetEditContext

    @State private var amountText = ""
    @State private var previousAmountText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(spacing: 4) {
                Text(context.categoryName ?? "月总预算")
                    .font(.title3.weight(.semibold))
                Text("\(FinanceFormat.monthLabel(context.month)) · 留空或输入 0 表示移除")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("¥")
                    .font(.system(size: 28, weight: .semibold, design: .rounded))
                    .foregroundStyle(.secondary)
                TextField("0.00", text: amountBinding)
                    .textFieldStyle(.plain)
                    .font(.system(size: 32, weight: .bold, design: .rounded))
                    .monospacedDigit()
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassEffect(.regular, in: .rect(cornerRadius: 18))

            HStack {
                Button("移除预算", role: .destructive) {
                    apply(limit: 0)
                }
                .disabled(context.currentLimit == 0)
                Spacer()
                Button("取消") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("保存") {
                    apply(limit: parseAmountInMinorUnits(amountText, allowZero: true) ?? 0)
                }
                .buttonStyle(.glassProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(parseAmountInMinorUnits(amountText, allowZero: true) == nil)
            }
        }
        .padding(24)
        .frame(width: 420)
        .onAppear {
            amountText = context.currentLimit == 0 ? "" : backfillText(context.currentLimit)
            previousAmountText = amountText
        }
    }

    private var amountBinding: Binding<String> {
        Binding(
            get: { amountText },
            set: { candidate in
                if let normalized = normalizeAmountInput(candidate) {
                    amountText = normalized
                    previousAmountText = normalized
                } else {
                    amountText = previousAmountText
                }
            }
        )
    }

    private func apply(limit: Int64) {
        if let categoryId = context.categoryId {
            store.setCategoryBudget(month: context.month, categoryId: categoryId, amountLimit: limit)
        } else {
            store.setTotalBudget(month: context.month, amountLimit: limit)
        }
        dismiss()
    }

    private func backfillText(_ minor: Int64) -> String {
        "\(minor / 100).\(String(format: "%02d", minor % 100))"
    }
}

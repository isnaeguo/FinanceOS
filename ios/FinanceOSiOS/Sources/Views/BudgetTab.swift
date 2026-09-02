import SwiftUI

/// 预算页：支持任意历史月份，未来最多到下月。
struct BudgetTab: View {
    @Environment(FinanceStore.self) private var store

    @State private var month: BudgetMonth = BudgetMonth.current
    @State private var editing: BudgetEditTarget?

    struct BudgetEditTarget: Identifiable {
        let id = UUID()
        let categoryId: String?
        let categoryName: String?
        let currentLimit: Int64
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    MonthSwitcher(
                        month: month,
                        canGoNext: month < BudgetMonth.current.next(),
                        onPrevious: { month = month.previous() },
                        onNext: { month = month.next() },
                        onBackToCurrent: { month = BudgetMonth.current }
                    )

                    totalBudgetCard
                    categoryBudgetsCard
                }
                .padding(.horizontal, 16)
            }
            .background(Color.fosPage.ignoresSafeArea())
            .navigationTitle("预算")
#if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
#endif
            .sheet(item: $editing) { target in
                BudgetAmountSheet(store: store, month: month, target: target)
            }
        }
    }

    private var status: MonthlyBudgetStatus { store.budgetStatus(in: month.period()) }

    private var totalBudgetCard: some View {
        let usage = status.total
        return SectionCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label("月总预算", systemImage: "target").font(.headline)
                    Spacer()
                    Button(usage.hasBudget ? "调整" : "设置") {
                        editing = BudgetEditTarget(categoryId: nil, categoryName: nil, currentLimit: usage.amountLimit ?? 0)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                if usage.hasBudget {
                    Text("\(formatMoney(usage.amountUsed)) / \(formatMoney(usage.amountLimit ?? 0))")
                        .font(.title2.weight(.semibold).monospacedDigit())
                    MiniProgressBar(ratio: usage.usageRatio, isOver: usage.isOverBudget)
                    if let remaining = usage.amountRemaining, !usage.isOverBudget {
                        Text("剩余 \(formatMoney(remaining))").font(.caption).foregroundStyle(.secondary)
                    } else if usage.isOverBudget {
                        Text("已超支").font(.caption.weight(.semibold)).foregroundStyle(.red)
                    }
                } else {
                    Text("尚未设置该月总预算").font(.subheadline).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var categoryBudgetsCard: some View {
        let budgetable = store.categories.filter { $0.accepts(.expense) }
        let summary = store.monthlySummary(in: month.period())
        return SectionCard {
            VStack(alignment: .leading, spacing: 10) {
                Label("分类预算", systemImage: "square.stack.3d.up.fill").font(.headline)
                ForEach(budgetable) { category in
                    let usage = status.categories[category.id]
                    HStack(spacing: 10) {
                        CategoryIconView(category: category, size: 28)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(category.name).font(.callout.weight(.medium))
                            Text(usage == nil
                                ? "本月支出 \(formatMoney(summary.expensesByCategory[category.id] ?? 0))"
                                : "\(formatMoney(usage?.amountUsed ?? 0)) / \(formatMoney(usage?.amountLimit ?? 0))")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if let usage {
                            MiniProgressBar(ratio: usage.usageRatio, isOver: usage.isOverBudget)
                                .frame(width: 90)
                        }
                        Button("编辑") {
                            editing = BudgetEditTarget(
                                categoryId: category.id,
                                categoryName: category.name,
                                currentLimit: status.categories[category.id]?.amountLimit ?? 0
                            )
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }
                }
            }
        }
    }
}

/// 预算金额输入（总预算或分类预算；0 表示移除）。
struct BudgetAmountSheet: View {
    @Environment(\.dismiss) private var dismiss
    let store: FinanceStore
    let month: BudgetMonth
    let target: BudgetTab.BudgetEditTarget

    @State private var amountText = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("\(target.categoryName ?? "月总预算") · \(FMT.monthLabel(month))") {
                    HStack {
                        Text("¥").foregroundStyle(.secondary)
                        TextField("0.00", text: amountBinding)
#if os(iOS)
                            .keyboardType(.decimalPad)
#endif
                            .font(.title2.weight(.semibold).monospacedDigit())
                    }
                }
                Section {
                    Button("移除预算", role: .destructive) {
                        apply(0)
                    }
                    .disabled(target.currentLimit == 0)
                }
            }
            .navigationTitle("设置预算")
#if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
#endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        apply(parseAmountInMinorUnits(amountText, allowZero: true) ?? 0)
                    }
                    .disabled(parseAmountInMinorUnits(amountText, allowZero: true) == nil)
                }
            }
            .onAppear {
                if target.currentLimit > 0 {
                    amountText = "\(target.currentLimit / 100).\(String(format: "%02lld", target.currentLimit % 100))"
                }
            }
        }
    }

    private var amountBinding: Binding<String> {
        Binding(
            get: { amountText },
            set: { candidate in
                if let normalized = normalizeAmountInput(candidate) {
                    amountText = normalized
                }
            }
        )
    }

    private func apply(_ limit: Int64) {
        if let categoryId = target.categoryId {
            store.setCategoryBudget(month: month, categoryId: categoryId, amountLimit: limit)
        } else {
            store.setTotalBudget(month: month, amountLimit: limit)
        }
        dismiss()
    }
}

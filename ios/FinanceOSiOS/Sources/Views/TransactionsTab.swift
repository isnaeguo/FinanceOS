import SwiftUI

enum AmountSortKind: String, CaseIterable {
    case timeDesc = "时间最新"
    case amountDesc = "金额最大"
    case amountAsc = "金额最小"
}

/// 流水页：按月浏览 + 搜索 + 排序 + 编辑/删除。
struct TransactionsTab: View {
    @Environment(FinanceStore.self) private var store

    @State private var month: BudgetMonth = BudgetMonth.current
    @State private var query = ""
    @State private var sort: AmountSortKind = .timeDesc
    @State private var editing: Transaction?
    @State private var deleting: Transaction?
    @State private var showAdd = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField("搜索备注/分类", text: $query)
                            .textFieldStyle(.roundedBorder)
                        Menu {
                            Picker("排序", selection: $sort) {
                                ForEach(AmountSortKind.allCases, id: \.self) { kind in
                                    Text(kind.rawValue).tag(kind)
                                }
                            }
                        } label: {
                            Image(systemName: "arrow.up.arrow.down")
                        }
                    }
                }

                if (sort == .timeDesc ? groupedDays.isEmpty : monthSorted.isEmpty) {
                    Text("没有符合条件的流水")
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .listRowSeparator(.hidden)
                }

                if sort == .timeDesc {
                    ForEach(groupedDays, id: \.day) { group in
                        Section {
                            ForEach(group.items) { transaction in
                                TransactionCell(transaction: transaction, category: store.category(id: transaction.categoryId))
                                    .contentShape(Rectangle())
                                    .contextMenu {
                                        Button("编辑") { editing = transaction }
                                        Button("删除", role: .destructive) { deleting = transaction }
                                    }
                            }
                        } header: {
                            HStack {
                                Text(FMT.dayLabel(group.day))
                                Spacer()
                                Text(dayNet(group.items))
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                } else {
                    // 金额排序：忽略天限制，整月按金额排列
                    ForEach(monthSorted) { transaction in
                        TransactionCell(transaction: transaction, category: store.category(id: transaction.categoryId))
                            .contentShape(Rectangle())
                            .contextMenu {
                                Button("编辑") { editing = transaction }
                                Button("删除", role: .destructive) { deleting = transaction }
                            }
                    }
                }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                HStack(spacing: 10) {
                    Button(action: { month = month.previous() }) {
                        Image(systemName: "chevron.left")
                            .font(.footnote.weight(.semibold))
                            .frame(width: 30, height: 30)
                            .background(Color.fosCard)
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)

                    Text(FMT.monthLabel(month))
                        .font(.footnote.weight(.semibold).monospacedDigit())
                        .frame(minWidth: 84)

                    Button(action: { month = month.next() }) {
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .frame(width: 30, height: 30)
                            .background(Color.fosCard)
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(month >= BudgetMonth.current)
                    .opacity(month < BudgetMonth.current ? 1 : 0.3)
                }
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity)
                .background(Color.fosPage)
            }
            .navigationTitle("流水")
#if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
#endif
            .toolbar {
                ToolbarItem(placement: fosTrailingPlacement()) {
                    Button { showAdd = true } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $showAdd) {
                AddTransactionSheetContent(store: store)
            }
            .sheet(item: $editing) { transaction in
                AddTransactionSheetContent(store: store, editing: transaction)
            }
            .confirmationDialog(
                "删除这笔流水？",
                isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) {
                    if let id = deleting?.id { store.deleteTransaction(id: id) }
                    deleting = nil
                }
                Button("取消", role: .cancel) { deleting = nil }
            }
        }
    }

    private struct DayGroup {
        let day: Date
        let items: [Transaction]
    }

    private var period: MonthPeriod { month.period() }

    private var filtered: [Transaction] {
        store.monthlyTransactions(in: period).filter { transaction in
            guard !query.isEmpty else { return true }
            let note = transaction.note?.localizedCaseInsensitiveContains(query) ?? false
            let category = store.category(id: transaction.categoryId)?.name.localizedCaseInsensitiveContains(query) ?? false
            return note || category
        }
    }

    private var groupedDays: [DayGroup] {
        var buckets: [Date: [Transaction]] = [:]
        for transaction in filtered {
            let day = Calendar.current.startOfDay(for: transaction.dateTime)
            buckets[day, default: []].append(transaction)
        }
        return buckets
            .map { bucket in
                DayGroup(day: bucket.key, items: sorted(bucket.value))
            }
            .sorted { $0.day > $1.day }
    }

    private var monthSorted: [Transaction] {
        switch sort {
        case .timeDesc:
            return filtered.sorted { $0.dateTime > $1.dateTime }
        case .amountDesc:
            return filtered.sorted { lhs, rhs in
                lhs.amount != rhs.amount ? lhs.amount > rhs.amount : lhs.dateTime > rhs.dateTime
            }
        case .amountAsc:
            return filtered.sorted { lhs, rhs in
                lhs.amount != rhs.amount ? lhs.amount < rhs.amount : lhs.dateTime > rhs.dateTime
            }
        }
    }

    private func sorted(_ items: [Transaction]) -> [Transaction] {
        switch sort {
        case .timeDesc:
            return items.sorted { $0.dateTime > $1.dateTime }
        case .amountDesc:
            return items.sorted { lhs, rhs in
                lhs.amount != rhs.amount ? lhs.amount > rhs.amount : lhs.dateTime > rhs.dateTime
            }
        case .amountAsc:
            return items.sorted { lhs, rhs in
                lhs.amount != rhs.amount ? lhs.amount < rhs.amount : lhs.dateTime > rhs.dateTime
            }
        }
    }

    private func dayNet(_ items: [Transaction]) -> String {
        var net: Int64 = 0
        for item in items {
            net += item.type == .income ? item.amount : -item.amount
        }
        return net >= 0 ? "+\(formatMoney(net))" : "-\(formatMoney(-net))"
    }
}

/// 紧凑月份切换（适合工具栏）。
struct MonthSwitcherCompact: View {
    let month: BudgetMonth
    let canGoNext: Bool
    let onPrevious: () -> Void
    let onNext: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onPrevious) { Image(systemName: "chevron.left") }
                .disabled(false)
            Text(FMT.monthLabel(month))
                .font(.headline.monospacedDigit())
                .frame(minWidth: 86)
            Button(action: onNext) { Image(systemName: "chevron.right") }
                .disabled(!canGoNext)
                .opacity(canGoNext ? 1 : 0.3)
        }
    }
}

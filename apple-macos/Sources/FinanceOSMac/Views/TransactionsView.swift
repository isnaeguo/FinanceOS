import SwiftUI
import FinanceOSCore

struct TransactionRow: View {
    let transaction: FinanceOSCore.Transaction
    let category: FinanceOSCore.Category?
    var showsTime: Bool = true

    var body: some View {
        HStack(spacing: 12) {
            CategoryIconView(category: category, size: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(primaryTitle)
                    .font(.callout.weight(.medium))
                    .lineLimit(1)
                if !secondaryTitle.isEmpty {
                    Text(secondaryTitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            Text(amountText)
                .font(.callout.weight(.semibold).monospacedDigit())
                .foregroundStyle(transaction.type == .income ? Color.mint : Color.primary)
        }
        .padding(.vertical, 2)
    }

    private var primaryTitle: String {
        let note = transaction.note?.trimmingCharacters(in: .whitespaces)
        if let note, !note.isEmpty { return note }
        return category?.name ?? "未知分类"
    }

    private var secondaryTitle: String {
        var parts: [String] = []
        if let note = transaction.note?.trimmingCharacters(in: .whitespaces), !note.isEmpty {
            parts.append(category?.name ?? "未知分类")
        }
        if showsTime {
            parts.append(FinanceFormat.timeLabel(transaction.dateTime))
        }
        if let account = transaction.accountId, !account.isEmpty {
            parts.append(account)
        }
        return parts.joined(separator: " · ")
    }

    private var amountText: String {
        switch transaction.type {
        case .income: "+\(formatMoney(transaction.amount))"
        case .expense: "-\(formatMoney(transaction.amount))"
        }
    }
}

/// 流水页的账户筛选。
enum AccountFilter: Hashable {
    case all
    case specific(String)
}

/// 流水组内排序方式：默认按时间倒序，也可按金额升/降序。
enum AmountSort: String, CaseIterable, Hashable {
    /// 按时间倒序（默认）。
    case timeDesc
    /// 金额从大到小，同额按时间倒序。
    case amountDesc
    /// 金额从小到大，同额按时间倒序。
    case amountAsc

    /// 工具栏菜单当前态短标签。
    var shortLabel: String {
        switch self {
        case .timeDesc: "时间"
        case .amountDesc: "金额↓"
        case .amountAsc: "金额↑"
        }
    }

    /// 菜单内完整说明。
    var optionLabel: String {
        switch self {
        case .timeDesc: "按时间（最新在前）"
        case .amountDesc: "金额从大到小"
        case .amountAsc: "金额从小到大"
        }
    }
}

struct TransactionsView: View {
    @Environment(FinanceStore.self) private var store
    @Environment(AppRouter.self) private var router

    @State private var selectedMonth: BudgetMonth = .current
    @State private var searchText = ""
    @State private var typeFilter: TransactionType?
    @State private var categoryFilter: String?
    @State private var accountFilter: AccountFilter = .all
    @State private var amountSort: AmountSort = .timeDesc
    @State private var pendingDelete: FinanceOSCore.Transaction?
    @State private var editingTransaction: FinanceOSCore.Transaction?

    var body: some View {
        VStack(spacing: 0) {
            list
                .padding(20)
        }
        .background(AuroraBackground())
        .navigationTitle("流水")
        .searchable(text: $searchText, placement: .toolbar, prompt: "搜索备注关键词")
        .toolbar { toolbarContent }
        .confirmationDialog(
            "删除这笔流水？",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                if let transaction = pendingDelete {
                    store.deleteTransaction(id: transaction.id)
                }
                pendingDelete = nil
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("删除后无法恢复。")
        }
        .sheet(item: $editingTransaction) { transaction in
            AddTransactionSheet(store: store, draft: TransactionDraft.editing(transaction))
        }
    }

    // MARK: - 列表

    private var list: some View {
        let groups = groupedDays
        let summary = filteredSummary
        return List {
            let sortingByAmount = amountSort != .timeDesc
            let monthAmountItems = sortingByAmount ? filtered.sorted(by: amountSortComparator) : []
            let isEmpty = sortingByAmount ? monthAmountItems.isEmpty : groupedDays.isEmpty
            if isEmpty {
                EmptyStateView(
                    symbol: "tray",
                    title: filtersActive ? "没有符合条件的流水" : "本月还没有流水",
                    message: filtersActive ? "试试清除筛选条件" : "点击右上角“记一笔”添加收入或支出"
                )
                .listRowSeparator(.hidden)
            } else if sortingByAmount {
                // 金额排序：忽略天限制，整月按金额排列
                ForEach(monthAmountItems) { transaction in
                    TransactionRow(
                        transaction: transaction,
                        category: store.category(id: transaction.categoryId)
                    )
                    .contentShape(Rectangle())
                    .onTapGesture(count: 2) {
                        editingTransaction = transaction
                    }
                    .contextMenu {
                        Button("编辑") { editingTransaction = transaction }
                        Divider()
                        Button("删除", role: .destructive) { pendingDelete = transaction }
                    }
                }
            } else {
                ForEach(groupedDays, id: \.day) { group in
                    Section {
                        ForEach(group.items) { transaction in
                            TransactionRow(
                                transaction: transaction,
                                category: store.category(id: transaction.categoryId)
                            )
                            .contentShape(Rectangle())
                            .onTapGesture(count: 2) {
                                editingTransaction = transaction
                            }
                            .contextMenu {
                                Button("编辑") { editingTransaction = transaction }
                                Divider()
                                Button("删除", role: .destructive) { pendingDelete = transaction }
                            }
                        }
                    } header: {
                        HStack {
                            Text(FinanceFormat.dayLabel(group.day))
                            Spacer()
                            Text(dayNetLabel(group))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .listStyle(.inset)
        .scrollContentBackground(.hidden)
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 14) {
                Label("\(filtered.count) 笔", systemImage: "number")
                Divider().frame(height: 12)
                Text("收入 \(formatMoney(summary.income))")
                    .foregroundStyle(.mint)
                Text("支出 \(formatMoney(summary.expense))")
                    .foregroundStyle(.primary.opacity(0.85))
                Spacer()
                if filtersActive {
                    Button("清除筛选") {
                        searchText = ""
                        typeFilter = nil
                        categoryFilter = nil
                        accountFilter = .all
                    }
                    .buttonStyle(.glass)
                    .controlSize(.small)
                }
            }
            .font(.caption)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .glassEffect(.regular, in: .capsule)
            .padding(.bottom, 12)
            .frame(maxWidth: .infinity, alignment: .center)
        }
    }

    // MARK: - 工具栏

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            HStack(spacing: 8) {
                Button {
                    selectedMonth = selectedMonth.previous()
                } label: {
                    Image(systemName: "chevron.left")
                }
                Text(FinanceFormat.monthLabel(selectedMonth))
                    .font(.headline.monospacedDigit())
                Button {
                    selectedMonth = selectedMonth.next()
                } label: {
                    Image(systemName: "chevron.right")
                }
                .disabled(selectedMonth >= BudgetMonth.current)
            }
        }
        ToolbarItemGroup {
            if !filtered.isEmpty {
                sortMenu
            }
            filterMenu
            Spacer()
            Button {
                router.isAddSheetPresented = true
            } label: {
                Label("记一笔", systemImage: "plus")
            }
            .buttonStyle(.glassProminent)
            .keyboardShortcut("n", modifiers: .command)
        }
    }

    private var sortMenu: some View {
        Menu {
            Picker("排序", selection: $amountSort) {
                ForEach(AmountSort.allCases, id: \.self) { mode in
                    Text(mode.optionLabel).tag(mode)
                }
            }
        } label: {
            Label("排序 · \(amountSort.shortLabel)", systemImage: "arrow.up.arrow.down")
        }
    }

    private var filterMenu: some View {
        Menu {
            Picker("类型", selection: $typeFilter) {
                Text("全部类型").tag(TransactionType?.none)
                ForEach(TransactionType.allCases, id: \.self) { type in
                    Text(type.label).tag(TransactionType?.some(type))
                }
            }
            Picker("分类", selection: $categoryFilter) {
                Text("全部分类").tag(String?.none)
                ForEach(store.categories, id: \.id) { category in
                    Text(category.name).tag(String?.some(category.id))
                }
            }
            Picker("账户", selection: $accountFilter) {
                Text("全部账户").tag(AccountFilter.all)
                ForEach(store.knownAccounts, id: \.self) { account in
                    Text(account).tag(AccountFilter.specific(account))
                }
            }
            Divider()
            Button("重置筛选", role: .destructive) {
                typeFilter = nil
                categoryFilter = nil
                accountFilter = .all
            }
            .disabled(!filtersActive)
        } label: {
            Label("筛选", systemImage: filtersActive ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
        }
    }

    // MARK: - 数据

    private struct DayGroup {
        let day: Date
        let items: [FinanceOSCore.Transaction]
    }

    private var filtersActive: Bool {
        !searchText.isEmpty || typeFilter != nil || categoryFilter != nil || accountFilter != .all
    }

    private var filtered: [FinanceOSCore.Transaction] {
        let period = selectedMonth.period()
        return store.monthlyTransactions(in: period).filter { transaction in
            if let typeFilter, transaction.type != typeFilter { return false }
            if let categoryFilter, transaction.categoryId != categoryFilter { return false }
            switch accountFilter {
            case .all: break
            case .specific(let account) where transaction.accountId != account: return false
            case .specific: break
            }
            if !searchText.isEmpty {
                let note = transaction.note?.localizedCaseInsensitiveContains(searchText) ?? false
                let category = store.category(id: transaction.categoryId)?.name.localizedCaseInsensitiveContains(searchText) ?? false
                if !note && !category { return false }
            }
            return true
        }
    }

    private var groupedDays: [DayGroup] {
        let calendar = Calendar.current
        var buckets: [Date: [FinanceOSCore.Transaction]] = [:]
        for transaction in filtered {
            let day = calendar.startOfDay(for: transaction.dateTime)
            buckets[day, default: []].append(transaction)
        }
        return buckets
            .map { DayGroup(day: $0.key, items: $0.value.sorted(by: amountSortComparator)) }
            .sorted { $0.day > $1.day }
    }

    /// 组内排序：默认按时间倒序；按金额时先比较金额，金额相等再按时间倒序。
    private func amountSortComparator(_ lhs: FinanceOSCore.Transaction, _ rhs: FinanceOSCore.Transaction) -> Bool {
        switch amountSort {
        case .timeDesc:
            return lhs.dateTime > rhs.dateTime
        case .amountDesc:
            if lhs.amount != rhs.amount { return lhs.amount > rhs.amount }
            return lhs.dateTime > rhs.dateTime
        case .amountAsc:
            if lhs.amount != rhs.amount { return lhs.amount < rhs.amount }
            return lhs.dateTime > rhs.dateTime
        }
    }

    private var filteredSummary: (income: Int64, expense: Int64) {
        var income: Int64 = 0
        var expense: Int64 = 0
        for transaction in filtered {
            switch transaction.type {
            case .income: income += transaction.amount
            case .expense: expense += transaction.amount
            }
        }
        return (income, expense)
    }

    private func dayNetLabel(_ group: DayGroup) -> String {
        var income: Int64 = 0
        var expense: Int64 = 0
        for transaction in group.items {
            switch transaction.type {
            case .income: income += transaction.amount
            case .expense: expense += transaction.amount
            }
        }
        let net = income - expense
        return net >= 0 ? "+\(formatMoney(net))" : "-\(formatMoney(-net))"
    }
}

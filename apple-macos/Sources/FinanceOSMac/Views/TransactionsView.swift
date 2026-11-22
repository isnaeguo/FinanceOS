import SwiftUI

struct TransactionRow: View, Equatable {
    let transaction: Transaction
    let category: Category?
    var showsTime: Bool = true

    var body: some View {
        let note = transaction.note?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let categoryName = category?.name ?? "未知分类"
        let subtitle = secondaryTitle(includeCategory: !note.isEmpty, categoryName: categoryName)
        HStack(spacing: 10) {
            CategoryIconView(category: category, size: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(note.isEmpty ? categoryName : note)
                    .font(.callout.weight(.medium))
                    .lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle)
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

    private func secondaryTitle(includeCategory: Bool, categoryName: String) -> String {
        var parts: [String] = []
        if includeCategory { parts.append(categoryName) }
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
enum AccountFilter: Hashable, Sendable {
    case all
    case specific(String)
}

/// 流水组内排序方式：默认按时间倒序，也可按金额升/降序。
enum AmountSort: String, CaseIterable, Hashable, Sendable {
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
    @State private var pendingDelete: Transaction?
    @State private var editingTransaction: Transaction?
    @State private var presentation: TransactionListPresentation?

    var body: some View {
        let query = TransactionListQuery(
            month: selectedMonth,
            text: searchText.trimmingCharacters(in: .whitespacesAndNewlines),
            type: typeFilter, category: categoryFilter, account: accountFilter, sort: amountSort,
            revision: store.ledgerRevision
        )
        VStack(spacing: 0) {
            if let presentation, presentation.query.month == query.month {
                list(presentation)
                Divider()
                footer(presentation, filtersActive: query.hasFilters)
            } else {
                FinanceLoading(label: "正在读取流水")
                    .controlSize(.small)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: query) { await prepare(query) }
        .navigationTitle("流水")
        .searchable(text: $searchText, placement: .toolbar, prompt: "搜索备注、分类或账户")
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

    private func list(_ presentation: TransactionListPresentation) -> some View {
        List {
            if presentation.count == 0 {
                EmptyStateView(
                    symbol: "tray",
                    title: presentation.query.hasFilters ? "没有符合条件的流水" : "本月还没有流水",
                    message: presentation.query.hasFilters ? "试试清除筛选条件" : "点击右上角“记一笔”添加收入或支出"
                )
                .listRowSeparator(.hidden)
            } else if presentation.query.sort != .timeDesc {
                ForEach(presentation.rows) { row($0) }
            } else {
                ForEach(presentation.days) { day in
                    Section {
                        ForEach(day.rows) { row($0) }
                    } header: {
                        HStack {
                            Text(FinanceFormat.dayLabel(day.date, calendar: store.calendar))
                            Spacer()
                            Text(day.totals.net >= 0 ? "+\(formatMoney(day.totals.net))" : formatMoney(day.totals.net))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .listStyle(.inset)
    }

    private func row(_ transaction: Transaction) -> some View {
        TransactionRow(transaction: transaction, category: store.category(id: transaction.categoryId))
            .equatable()
            .contentShape(Rectangle())
            .onTapGesture(count: 2) { editingTransaction = transaction }
            .contextMenu {
                Button("编辑") { editingTransaction = transaction }
                Divider()
                Button("删除", role: .destructive) { pendingDelete = transaction }
            }
    }

    private func footer(_ presentation: TransactionListPresentation, filtersActive: Bool) -> some View {
        HStack(spacing: 14) {
            Text("\(presentation.count) 笔").foregroundStyle(.secondary)
            Text("收入 \(formatMoney(presentation.totals.income))")
            Text("支出 \(formatMoney(presentation.totals.expense))")
            Spacer(minLength: 8)
            if filtersActive {
                Button("清除筛选") {
                    searchText = ""
                    typeFilter = nil
                    categoryFilter = nil
                    accountFilter = .all
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
            }
        }
        .font(.caption.monospacedDigit())
        .padding(.horizontal, 22)
        .padding(.vertical, 10)
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
            sortMenu
            filterMenu
            Button {
                router.isAddSheetPresented = true
            } label: {
                Label("记一笔", systemImage: "plus")
            }
            .buttonStyle(.borderedProminent)
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
            .disabled(!hasFilters)
        } label: {
            Label("筛选", systemImage: hasFilters ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
        }
    }

    private var hasFilters: Bool {
        !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            typeFilter != nil || categoryFilter != nil || accountFilter != .all
    }

    private func prepare(_ query: TransactionListQuery) async {
        if !query.text.isEmpty, presentation?.query.text != query.text {
            do { try await Task.sleep(for: .milliseconds(120)) }
            catch { return }
        }
        guard !Task.isCancelled else { return }
        let calendar = store.calendar
        let slice = store.transactionSlice(in: query.month.period(calendar: calendar))
        let categories = query.text.isEmpty ? [:] : store.categoryLookup
        let worker = Task.detached(priority: .userInitiated) {
            TransactionListPresentation.make(query: query, rows: slice, categories: categories, calendar: calendar)
        }
        await withTaskCancellationHandler {
            if let result = await worker.value, !Task.isCancelled { presentation = result }
        } onCancel: {
            worker.cancel()
        }
    }
}

struct TransactionListQuery: Equatable, Sendable {
    let month: BudgetMonth
    let text: String
    let type: TransactionType?
    let category: String?
    let account: AccountFilter
    let sort: AmountSort
    let revision: UInt64

    var hasFilters: Bool { !text.isEmpty || type != nil || category != nil || account != .all }

    func matches(_ row: Transaction, categories: [String: Category]) -> Bool {
        guard !row.isDeleted, type == nil || row.type == type, category == nil || row.categoryId == category else {
            return false
        }
        if case .specific(let id) = account, row.accountId != id { return false }
        return text.isEmpty || row.note?.localizedCaseInsensitiveContains(text) == true ||
            row.accountId?.localizedCaseInsensitiveContains(text) == true ||
            categories[row.categoryId]?.name.localizedCaseInsensitiveContains(text) == true
    }
}

struct TransactionListPresentation: Sendable {
    struct Totals: Sendable {
        private(set) var income: Int64 = 0
        private(set) var expense: Int64 = 0
        var net: Int64 { income - expense }

        mutating func add(_ row: Transaction) {
            switch row.type {
            case .income: income = Self.saturatingAdd(income, row.amount)
            case .expense: expense = Self.saturatingAdd(expense, row.amount)
            }
        }

        private static func saturatingAdd(_ current: Int64, _ amount: Int64) -> Int64 {
            let (sum, overflow) = current.addingReportingOverflow(amount)
            return overflow ? .max : sum
        }
    }

    struct Day: Identifiable, Sendable {
        let date: Date
        var rows: [Transaction] = []
        var totals = Totals()
        var id: Date { date }
    }

    let query: TransactionListQuery
    let count: Int
    let totals: Totals
    let days: [Day]
    let rows: [Transaction]

    static func make(
        query: TransactionListQuery,
        rows: ArraySlice<Transaction>,
        categories: [String: Category],
        calendar: Calendar
    ) -> TransactionListPresentation? {
        var totals = Totals()
        var count = 0
        var days: [Day] = []
        var flatRows: [Transaction] = []
        for (offset, row) in rows.enumerated() {
            if offset.isMultiple(of: 256), Task.isCancelled { return nil }
            guard query.matches(row, categories: categories) else { continue }
            count += 1
            totals.add(row)
            if query.sort == .timeDesc {
                if days.last.map({ row.dateTime < $0.date }) ?? true {
                    days.append(Day(date: calendar.startOfDay(for: row.dateTime)))
                }
                let index = days.count - 1
                days[index].rows.append(row)
                days[index].totals.add(row)
            } else {
                flatRows.append(row)
            }
        }
        if query.sort != .timeDesc {
            flatRows.sort {
                if $0.amount != $1.amount {
                    return query.sort == .amountDesc ? $0.amount > $1.amount : $0.amount < $1.amount
                }
                return $0.dateTime != $1.dateTime ? $0.dateTime > $1.dateTime : $0.id < $1.id
            }
        }
        return Task.isCancelled ? nil : TransactionListPresentation(
            query: query, count: count, totals: totals, days: days, rows: flatRows
        )
    }
}

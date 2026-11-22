@preconcurrency import FinanceOSShared
import AppKit
import Charts
import PDFKit
import SwiftUI
import UniformTypeIdentifiers
import Vision

@MainActor
@Observable
final class MacFinanceWorkspace {
    nonisolated private static let maxDocumentBytes = 16 * 1024 * 1024
    private(set) var state: FinanceWorkspaceState?
    private(set) var revision: UInt64 = 0
    private(set) var error: String?
    private var directory: URL?
    private var loading = false
    let conversation = FinanceConversationSession()

    func load(beside database: URL) async {
        guard state == nil, !loading else { return }
        let folder = database.deletingLastPathComponent().appendingPathComponent("Workspace", isDirectory: true)
        let file = folder.appendingPathComponent("workspace.json")
        loading = true
        defer { loading = false }
        do {
            state = try await Task.detached {
                let codec = FinanceWorkspaceCodec.shared
                guard FileManager.default.fileExists(atPath: file.path) else { return codec.empty() }
                let attributes = try file.resourceValues(forKeys: [.fileSizeKey])
                guard (attributes.fileSize ?? 0) <= Self.maxDocumentBytes else {
                    throw WorkspaceFailure("工作台配置过大。")
                }
                return try codec.decode(text: String(contentsOf: file, encoding: .utf8))
            }.value
            directory = folder
            revision &+= 1
        } catch { self.error = error.localizedDescription }
    }

    func update(_ change: (FinanceWorkspaceState) throws -> FinanceWorkspaceState) throws {
        guard let directory, let state else { throw WorkspaceFailure("工作台尚未打开。") }
        let next = try change(state)
        let data = Data(try FinanceWorkspaceCodec.shared.encode(state: next).utf8)
        guard data.count <= Self.maxDocumentBytes else { throw WorkspaceFailure("工作台配置过大。") }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: directory.appendingPathComponent("workspace.json"), options: .atomic)
        self.state = next
        revision &+= 1
    }

    func keepAttachment(_ data: Data, extension fileExtension: String) throws -> String {
        guard let directory else { throw WorkspaceFailure("工作台尚未打开。") }
        let attachments = directory.appendingPathComponent("Attachments", isDirectory: true)
        try FileManager.default.createDirectory(at: attachments, withIntermediateDirectories: true)
        let safeExtension = fileExtension.lowercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
        let name = UUID().uuidString.lowercased() + (safeExtension.isEmpty ? "" : "." + String(safeExtension.prefix(10)))
        try data.write(to: attachments.appendingPathComponent(name), options: .atomic)
        return name
    }

    func attachmentURL(_ name: String) -> URL? {
        guard name == URL(fileURLWithPath: name).lastPathComponent, !name.isEmpty else { return nil }
        return directory?.appendingPathComponent("Attachments", isDirectory: true).appendingPathComponent(name)
    }
}

private struct WorkspaceFailure: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

private enum WorkspaceTab: String, Identifiable, CaseIterable {
    case inbox = "收件箱"
    case accounts = "账户与对账"
    case forecast = "现金流"
    case books = "账本"
    case audit = "AI 助手"
    var id: String { rawValue }

    var glyph: FinanceGlyph {
        switch self {
        case .inbox: .inbox
        case .accounts: .accounts
        case .forecast: .forecast
        case .books: .books
        case .audit: .chat
        }
    }
}

private enum WorkspaceForm: String, Identifiable {
    case account = "新建账户"
    case checkpoint = "添加对账余额"
    case link = "关联流水"
    case book = "新建账本"
    case assignment = "归档流水"
    case plan = "计划收支"
    case item = "新建待办"
    case rule = "新建分类规则"
    var id: String { rawValue }
}

struct FinanceWorkspaceView: View {
    @Environment(FinanceStore.self) private var store
    @Environment(AppRouter.self) private var router
    @Environment(MacFinanceWorkspace.self) private var workspace
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var tab: WorkspaceTab = .inbox
    @State private var days = 30
    @State private var scenario = ""
    @State private var form: WorkspaceForm?
    @State private var editingEntityId: String?
    @State private var status = ""
    @State private var working = false
    @State private var showCompleted = false
    @State private var editingTransaction: Transaction?

    @State private var showsRules = false
    @State private var suggestedRules: [WorkspaceRuleSuggestion] = []

    @State private var summary: WorkspaceSummary?
    @State private var netWorth: [WorkspaceNetWorthPoint] = []
    @State private var forecast: WorkspaceForecast?
    @State private var inboxItems: [WorkspaceInboxItem] = []

    private var bookId: String {
        get { router.workspaceBookId }
        nonmutating set { router.workspaceBookId = newValue }
    }
    private var book: String? { bookId.isEmpty ? nil : bookId }

    private struct LoadKey: Equatable {
        let revision: UInt64
        let ledgerRevision: UInt64
        let tab: WorkspaceTab
        let book: String?
        let days: Int
        let scenario: String
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 21) {
                header
                if let state = workspace.state {
                    section(state)
                        .id(tab)
                        .transition(reduceMotion ? .identity : FinanceMotion.reveal)
                } else if let error = workspace.error {
                    ContentUnavailableView(
                        "工作台未打开", systemImage: "exclamationmark.triangle", description: Text(error)
                    )
                } else {
                    FinancePanel {
                        FinanceLoading(label: "正在读取工作台")
                            .frame(maxWidth: .infinity, minHeight: 180)
                    }
                }
            }
            .frame(maxWidth: 1050, alignment: .leading)
            .padding(.horizontal, 30)
            .padding(.vertical, 28)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("财务工作台")
        .task(id: LoadKey(
            revision: workspace.revision, ledgerRevision: store.ledgerRevision,
            tab: tab, book: book, days: days, scenario: scenario
        )) {
            await refresh()
        }
        .sheet(item: $form, onDismiss: { editingEntityId = nil }) { kind in
            if let state = workspace.state {
                WorkspaceEditor(kind: kind, state: state, activeBook: book, editingId: editingEntityId) { change in
                    try workspace.update(change)
                    status = "已保存"
                }
            }
        }
        .sheet(item: $editingTransaction) { transaction in
            AddTransactionSheet(store: store, draft: TransactionDraft.editing(transaction))
        }
        .onChange(of: bookId) {
            inboxItems = []
            forecast = nil
            scenario = ""
            suggestedRules = []
            workspace.conversation.selectBook(book)
        }
        .onChange(of: router.showWorkspaceChat, initial: true) {
            if router.showWorkspaceChat {
                tab = .audit
                showsRules = false
                router.showWorkspaceChat = false
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("财务工作台").font(.system(size: 35, weight: .medium, design: .default))
                        .tracking(0.35)
                    Text(Date.now.formatted(date: .abbreviated, time: .omitted))
                        .font(.callout).foregroundStyle(WorkspacePalette.secondary)
                }
                Spacer()
                if let state = workspace.state, !state.books.isEmpty {
                    Picker("范围", selection: Binding(get: { bookId }, set: { bookId = $0 })) {
                        Text("全账本").tag("")
                        ForEach(state.books, id: \.id) { entry in Text(entry.name).tag(entry.id) }
                    }
                    .fixedSize()
                }
                Button("记一笔", systemImage: "plus") { router.isAddSheetPresented = true }
                    .buttonStyle(.borderedProminent)
            }

            HStack(spacing: 4) {
                ForEach(WorkspaceTab.allCases) { item in
                    Button {
                        withAnimation(reduceMotion ? nil : FinanceMotion.short) {
                            tab = item
                            status = ""
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 8) {
                                item.glyph.stroke(style: StrokeStyle(lineWidth: 1.25, lineCap: .round, lineJoin: .round))
                                    .frame(width: 17, height: 17)
                                    .accessibilityHidden(true)
                                Text(item.rawValue)
                                    .font(.callout.weight(tab == item ? .semibold : .regular))
                            }
                            .foregroundStyle(tab == item ? Color.primary : WorkspacePalette.secondary)
                            Rectangle().fill(tab == item ? WorkspacePalette.accent : .clear).frame(height: 2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12)
                        .padding(.top, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(tab == item ? .isSelected : [])
                }
            }
            .overlay(alignment: .bottom) { Rectangle().fill(WorkspacePalette.border).frame(height: 1).allowsHitTesting(false) }
            if !status.isEmpty {
                Text(status).font(.callout).foregroundStyle(WorkspacePalette.secondary).textSelection(.enabled)
            }
        }
    }

    @ViewBuilder private func section(_ state: FinanceWorkspaceState) -> some View {
        switch tab {
        case .inbox: inboxSection(state)
        case .accounts: accountsSection(state)
        case .forecast: forecastSection(state)
        case .books: booksSection(state)
        case .audit: auditSection(state)
        }
    }

    private func refresh() async {
        guard let state = workspace.state, tab != .audit else { return }
        let rows = store.ledger.records
        let tab = tab
        let book = book
        let days = days
        let scenario = scenario.isEmpty ? nil : scenario
        let now = SystemEpochClock().nowMillis()
        let day = workspaceEpochDay(Date())
        let calendar = store.calendar
        let cutoffs = (1...12).reversed().compactMap {
            calendar.date(byAdding: .day, value: -7 * $0, to: Date(timeIntervalSince1970: Double(now) / 1_000))?.epochMillis
        } + [now]
        let worker = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            let transactions = rows.map { $0.toKMP() }
            try Task.checkCancellation()
            let analytics = FinanceWorkspaceAnalytics()
            switch tab {
            case .inbox:
                let scoped = try analytics.transactionsForBook(state: state, transactions: transactions, bookId: book)
                let items = WorkspaceAutomation().reviewItems(state: state.automation, bookId: book, transactions: scoped)
                return WorkspaceProjection(inbox: items)
            case .accounts, .books:
                let result = try analytics.summary(state: state, transactions: transactions, nowEpochMillis: now)
                let worth = tab == .books && book == nil && state.accounts.contains { $0.currency == "CNY" }
                    ? try analytics.netWorth(state: state, transactions: transactions, currency: "CNY", cutoffs: cutoffs.map { KotlinLong(value: $0) })
                    : []
                return WorkspaceProjection(summary: result, netWorth: worth)
            case .forecast:
                let result = try analytics.forecastForBook(
                    state: state, transactions: transactions, nowEpochMillis: now, startEpochDay: day,
                    days: Int32(days), scenario: scenario, bookId: book
                )
                return WorkspaceProjection(forecast: result)
            case .audit:
                return WorkspaceProjection()
            }
        }
        do {
            let result = try await withTaskCancellationHandler {
                try await worker.value
            } onCancel: { worker.cancel() }
            guard !Task.isCancelled else { return }
            if let summary = result.summary { self.summary = summary }
            if let netWorth = result.netWorth, tab == .books { self.netWorth = netWorth }
            if let forecast = result.forecast { self.forecast = forecast }
            if let items = result.inbox { inboxItems = items }
        } catch {
            if !Task.isCancelled { status = error.localizedDescription }
        }
    }
}

private struct WorkspaceProjection {
    var summary: WorkspaceSummary?
    var forecast: WorkspaceForecast?
    var inbox: [WorkspaceInboxItem]?
    var netWorth: [WorkspaceNetWorthPoint]?
}

private func workspaceEpochDay(_ date: Date) -> Int64 {
    let local = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day], from: date)
    var utc = Calendar(identifier: .gregorian)
    utc.timeZone = TimeZone(secondsFromGMT: 0)!
    return Int64((utc.date(from: local)?.timeIntervalSince1970 ?? 0) / 86_400)
}

private func workspaceDate(_ epochDay: Int64) -> Date {
    var utc = Calendar(identifier: .gregorian)
    utc.timeZone = TimeZone(secondsFromGMT: 0)!
    let date = Date(timeIntervalSince1970: TimeInterval(epochDay) * 86_400)
    var local = utc.dateComponents([.year, .month, .day], from: date)
    local.hour = 12
    return Calendar(identifier: .gregorian).date(from: local) ?? date
}

private func workspaceMinor(_ input: String, allowNegative: Bool = false) throws -> Int64 {
    let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
    let negative = trimmed.hasPrefix("-")
    guard !negative || allowNegative,
          let normalized = MoneyInput.parseAmountInMinorUnits(
            negative ? String(trimmed.dropFirst()) : trimmed, allowZero: allowNegative
          ) else {
        throw WorkspaceFailure("请输入有效金额，最多两位小数。")
    }
    return negative ? -normalized : normalized
}

private func workspaceAmount(_ amount: Int64, currency: String) -> String {
    if currency == "CNY" { return formatMoney(amount) }
    let value = Decimal(amount) / 100
    return "\(value.formatted(.number.precision(.fractionLength(2)))) \(currency)"
}

private func workspaceLabel(_ transaction: Transaction) -> String {
    let note = transaction.note ?? transaction.accountId ?? "流水"
    let date = transaction.dateTime.formatted(.dateTime.month().day())
    return "\(note.prefix(36)) · \(date) · \(formatMoney(transaction.amount))"
}

extension FinanceWorkspaceView {
    private func inboxSection(_ state: FinanceWorkspaceState) -> some View {
        let items = showCompleted ? state.automation.items.filter { $0.bookId == book && $0.completed } : inboxItems
        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                Button("导入账单", systemImage: "rectangle.and.arrow.down") { router.section = .data }
                Button("添加凭证", systemImage: "doc.viewfinder") { pickAttachment() }
                Button("新建待办", systemImage: "plus") { form = .item }
                Spacer()
                Button(showCompleted ? "显示待办" : "查看已处理") { showCompleted.toggle() }
                if working { FinanceLoading(compact: true) }
            }
            .buttonStyle(.bordered)
            .disabled(working)

            FinancePanel {
                LazyVStack(alignment: .leading, spacing: 0) {
                    Text(showCompleted ? "已处理" : "待处理").font(.title3.weight(.medium)).padding(.bottom, 8)
                    if items.isEmpty {
                        Text(showCompleted ? "现在没有已处理事项。" : "现在没有待处理事项。")
                            .font(.callout).foregroundStyle(WorkspacePalette.secondary)
                            .padding(.vertical, 14)
                    }
                    ForEach(items, id: \.id) { item in
                        Divider()
                        inboxRow(item)
                    }
                }
            }
        }
        .dropDestination(for: URL.self) { urls, _ in
            guard let url = urls.first, !working else { return false }
            if ["csv", "tsv", "xlsx", "ofx", "qfx", "qbo"].contains(url.pathExtension.lowercased()) {
                router.droppedStatement = url
                router.section = .data
            } else {
                addAttachment(url)
            }
            return true
        }
    }

    private func inboxRow(_ item: WorkspaceInboxItem) -> some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 5) {
                Text(item.title).font(.callout.weight(.medium))
                HStack(spacing: 8) {
                    Text(workspaceInboxTitle(item.kind))
                    if item.amountMinor > 0 { Text(formatMoney(item.amountMinor)).monospacedDigit() }
                    if item.dueAtEpochMillis > 0 {
                        Text(Date(timeIntervalSince1970: Double(item.dueAtEpochMillis) / 1000)
                            .formatted(.dateTime.month().day()))
                    }
                    if let name = item.attachmentName { Text(name).lineLimit(1) }
                }
                .font(.caption).foregroundStyle(WorkspacePalette.secondary)
                if !item.note.isEmpty {
                    Text(item.note).font(.caption).foregroundStyle(WorkspacePalette.secondary).lineLimit(3)
                }
            }
            Spacer(minLength: 10)
            if let id = item.transactionIds.first, let transaction = store.transaction(id: id) {
                Button("打开流水") { editingTransaction = transaction }
            }
            if let reference = item.attachmentReference, let file = workspace.attachmentURL(reference) {
                Button("凭证", systemImage: "arrow.up.right.square") { NSWorkspace.shared.open(file) }
            }
            if workspace.state?.automation.items.contains(where: { $0.id == item.id }) == true {
                Button("编辑") { edit(.item, id: item.id) }
            }
            Button(item.completed ? "重新打开" : "处理完成", systemImage: item.completed ? "arrow.uturn.backward" : "checkmark") { resolveItem(item) }
                .disabled(working)
        }
        .controlSize(.small)
        .buttonStyle(.bordered)
        .padding(.vertical, 12)
    }

    private func resolveItem(_ item: WorkspaceInboxItem) {
        mutate { state in
            let automation = WorkspaceAutomation()
            let next: WorkspaceAutomationState
            if let saved = state.automation.items.first(where: { $0.id == item.id }), saved.completed {
                next = try automation.saveItem(state: state.automation, item: saved.doCopy(
                    id: saved.id, bookId: saved.bookId, kind: saved.kind, title: saved.title, note: saved.note,
                    dueAtEpochMillis: saved.dueAtEpochMillis, amountMinor: saved.amountMinor, transactionIds: saved.transactionIds,
                    attachmentName: saved.attachmentName, attachmentReference: saved.attachmentReference, completed: false
                ))
            } else {
                next = try automation.completeItem(state: state.automation, id: item.id)
            }
            return FinanceWorkspace().saveAutomation(state: state, automation: next)
        }
    }

    private func pickAttachment() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = false
        panel.allowedContentTypes = [.image, .pdf, .plainText]
        if panel.runModal() == .OK, let url = panel.url { addAttachment(url) }
    }

    private func addAttachment(_ url: URL) {
        guard !working else { return }
        let book = book
        working = true
        status = ""
        Task {
            defer { working = false }
            do {
                let content = try await Task.detached(priority: .userInitiated) { try readWorkspaceAttachment(url) }.value
                let reference = try workspace.keepAttachment(content.data, extension: url.pathExtension)
                do {
                    try workspace.update { state in
                        let name = url.lastPathComponent
                        let item = try WorkspaceInboxItem(
                            id: UUID().uuidString, bookId: book, kind: .receipt,
                            title: String(name.prefix(160)), note: content.text, dueAtEpochMillis: 0, amountMinor: 0,
                            transactionIds: [], attachmentName: name, attachmentReference: reference, completed: false
                        )
                        let next = try WorkspaceAutomation().saveItem(state: state.automation, item: item)
                        return FinanceWorkspace().saveAutomation(state: state, automation: next)
                    }
                } catch {
                    if let saved = workspace.attachmentURL(reference) { try? FileManager.default.removeItem(at: saved) }
                    throw error
                }
                status = content.text.isEmpty ? "凭证已保存" : "凭证已保存，文字已在本机识别"
            } catch { status = error.localizedDescription }
        }
    }

    private func accountsSection(_ state: FinanceWorkspaceState) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Button("账户", systemImage: "plus") { form = .account }
                Button("核对余额", systemImage: "checkmark.seal") { form = .checkpoint }
                    .disabled(state.accounts.isEmpty)
                Button("关联转账、退款或重复", systemImage: "link") { form = .link }
                Spacer()
            }
            .buttonStyle(.bordered)
            if let summary {
                FinancePanel {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("账户余额").font(.title3.weight(.medium))
                        if summary.accountBalances.isEmpty {
                            Text("建立银行卡、信用卡或现金账户，录入期初余额。")
                                .font(.callout).foregroundStyle(WorkspacePalette.secondary)
                        }
                        ForEach(summary.accountBalances, id: \.accountId) { entry in
                            HStack {
                                Text(entry.name)
                                Spacer()
                                Text(workspaceAmount(entry.balanceMinor, currency: entry.currency))
                                    .font(.title3.monospacedDigit())
                                Button("编辑") { edit(.account, id: entry.accountId) }.buttonStyle(.bordered)
                            }
                            Divider()
                        }
                        Text("待归属流水 \(summary.unassignedCount) 笔")
                            .font(.caption).foregroundStyle(WorkspacePalette.secondary)
                        if summary.unresolvedLinkCount > 0 {
                            Text("\(summary.unresolvedLinkCount) 处关联因原流水修改或删除需要复核")
                                .font(.caption).foregroundStyle(.orange)
                        }
                        Button("归属到账户", systemImage: "tray.and.arrow.down") { form = .assignment }
                            .buttonStyle(.bordered)
                    }
                }
                if !summary.reconciliations.isEmpty {
                    FinancePanel {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("对账记录").font(.title3.weight(.medium))
                            ForEach(summary.reconciliations, id: \.checkpointId) { entry in
                                HStack {
                                    Text(state.accounts.first { $0.id == entry.accountId }?.name ?? "账户")
                                    Text(Date(timeIntervalSince1970: Double(entry.asOfEpochMillis) / 1000)
                                        .formatted(.dateTime.year().month().day()))
                                        .font(.caption).foregroundStyle(WorkspacePalette.secondary)
                                    Spacer()
                                    let currency = state.accounts.first { $0.id == entry.accountId }?.currency ?? "CNY"
                                    Text(entry.unvaluedCount > 0 ? "\(entry.unvaluedCount) 笔待补原币金额" : entry.differenceMinor == 0 ? "已对平" : "差额 \(workspaceAmount(entry.differenceMinor, currency: currency))")
                                        .monospacedDigit()
                                    Button("编辑") { edit(.checkpoint, id: entry.checkpointId) }.buttonStyle(.bordered)
                                }
                            }
                        }
                    }
                }
            } else {
                FinanceLoading(label: "正在汇总账户")
            }
            if !state.links.isEmpty {
                FinancePanel {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        Text("流水关联").font(.title3.weight(.medium))
                        ForEach(state.links, id: \.sourceId) { link in
                            HStack {
                                Text(workspaceLinkTitle(link.kind))
                                if let first = store.transaction(id: link.sourceId) { Text(workspaceLabel(first)).lineLimit(1) }
                                if let second = store.transaction(id: link.targetId) { Text(workspaceLabel(second)).lineLimit(1) }
                                Spacer()
                                Button("解除", role: .destructive) {
                                    mutate { FinanceWorkspace().removeLink(state: $0, sourceId: link.sourceId) }
                                }.buttonStyle(.bordered)
                            }
                            .font(.callout)
                        }
                    }
                }
            }
        }
    }

    private func forecastSection(_ state: FinanceWorkspaceState) -> some View {
        let plans = state.plans.filter { book == nil || $0.bookId == book }
        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                Picker("预测天数", selection: $days) {
                    Text("未来 30 天").tag(30)
                    Text("未来 90 天").tag(90)
                }.pickerStyle(.segmented).frame(width: 210)
                let scenarios = Set(plans.compactMap(\.scenario)).sorted()
                if !scenarios.isEmpty {
                    Picker("情景", selection: $scenario) {
                        Text("常规计划").tag("")
                        ForEach(scenarios, id: \.self) { Text($0).tag($0) }
                    }.fixedSize()
                }
                Spacer()
                Button("计划收支", systemImage: "plus") { form = .plan }.buttonStyle(.bordered)
            }
            if let forecast {
                FinancePanel {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .top, spacing: 18) {
                            workspaceMetric(book == nil ? "预测起点" : "账本累计净额", amount: forecast.openingBalanceMinor)
                            workspaceMetric("预计期末余额", amount: forecast.closingBalanceMinor)
                            workspaceMetric("区间最低", amount: forecast.lowBalanceMinor)
                        }
                        Text("预计收入 \(formatMoney(forecast.incomeMinor)) · 预计支出 \(formatMoney(forecast.expenseMinor))")
                            .foregroundStyle(WorkspacePalette.secondary).font(.callout)
                        Chart(forecast.points, id: \.epochDay) { point in
                            LineMark(
                                x: .value("日期", workspaceDate(point.epochDay)),
                                y: .value("预计余额", Double(point.balanceMinor) / 100)
                            ).foregroundStyle(WorkspacePalette.accent)
                        }
                        .chartYAxis { AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) }
                        .frame(height: 170)
                        .accessibilityLabel("预计余额时间轴")
                    }
                }
            } else { FinanceLoading(label: "正在计算现金流") }
            FinancePanel {
                LazyVStack(alignment: .leading, spacing: 12) {
                    Text("计划收支").font(.title3.weight(.medium))
                    if plans.isEmpty {
                        Text("添加工资、房租、续费或分期。可用情景名称将试算与常规计划分开。")
                            .font(.callout).foregroundStyle(WorkspacePalette.secondary)
                    }
                    ForEach(plans, id: \.id) { entry in
                        HStack {
                            Text(entry.name)
                            Text(workspaceRepeatTitle(entry.repeat)).foregroundStyle(WorkspacePalette.secondary)
                            if let scenario = entry.scenario { Text(scenario).foregroundStyle(WorkspacePalette.secondary) }
                            Spacer()
                            Text(entry.type == .income ? "+" + formatMoney(entry.amountMinor) : "-" + formatMoney(entry.amountMinor))
                                .monospacedDigit()
                            Button("编辑") { edit(.plan, id: entry.id) }.buttonStyle(.bordered)
                            Button("删除", role: .destructive) {
                                mutate { FinanceWorkspace().removePlan(state: $0, id: entry.id) }
                            }.buttonStyle(.bordered)
                        }.font(.callout)
                    }
                }
            }
        }
    }

    private func workspaceMetric(_ label: String, amount: Int64) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(label).font(.caption).foregroundStyle(WorkspacePalette.secondary)
            Text(formatMoney(amount)).font(.title2.weight(.medium).monospacedDigit())
                .foregroundStyle(amount < 0 ? WorkspacePalette.negative : .primary)
                .lineLimit(1).minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func booksSection(_ state: FinanceWorkspaceState) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Button("新建账本", systemImage: "plus") { form = .book }
                Button("归档或拆分流水", systemImage: "tray.and.arrow.down") { form = .assignment }
                if let book, let matching = state.books.first(where: { $0.id == book }) {
                    Button("导出「\(matching.name)」", systemImage: "square.and.arrow.up") { exportBook(matching, state: state) }
                }
                Spacer()
                Button("导出工作台配置", systemImage: "ellipsis.rectangle") { exportConfiguration(state) }
            }.buttonStyle(.bordered)
            FinancePanel {
                LazyVStack(alignment: .leading, spacing: 12) {
                    Text("账本").font(.title3.weight(.medium))
                    if state.books.isEmpty {
                        Text("可以按家庭、旅行或工作归档，独立管理票据、报销和收支。")
                            .font(.callout).foregroundStyle(WorkspacePalette.secondary)
                    }
                    ForEach(state.books, id: \.id) { entry in
                        HStack {
                            Button(entry.name) { bookId = entry.id }.buttonStyle(.plain)
                            Text(entry.currency).font(.caption).foregroundStyle(WorkspacePalette.secondary)
                            Spacer()
                            if let balance = summary?.bookBalances.first(where: { $0.bookId == entry.id }) {
                                Text("收入 \(workspaceAmount(balance.incomeMinor, currency: entry.currency))").monospacedDigit()
                                Text("支出 \(workspaceAmount(balance.expenseMinor, currency: entry.currency))").monospacedDigit()
                            }
                            Button("编辑") { edit(.book, id: entry.id) }.buttonStyle(.bordered)
                        }
                        Divider()
                    }
                    if let book {
                        ForEach(Array(state.assignments.lazy.filter { $0.bookId == book || $0.splits.contains { $0.bookId == book } }.prefix(30)), id: \.transactionId) { assignment in
                            if let transaction = store.transaction(id: assignment.transactionId) {
                                HStack {
                                    Text(workspaceLabel(transaction)).lineLimit(1)
                                    Spacer()
                                    if let currency = assignment.originalCurrency, let amount = assignment.originalAmountMinor {
                                        Text(workspaceAmount(amount.int64Value, currency: currency)).monospacedDigit()
                                    }
                                    Button("查看") { editingTransaction = transaction }.buttonStyle(.bordered)
                                    Button("归档设置") { edit(.assignment, id: transaction.id) }.buttonStyle(.bordered)
                                }.font(.callout)
                            }
                        }
                    }
                }
            }
            if book == nil, let summary {
                let cny = summary.accountBalances.filter { $0.currency == "CNY" }
                let assets = cny.filter { $0.balanceMinor > 0 }.reduce(Int64(0)) { workspaceSaturatingSum($0, $1.balanceMinor) }
                let liabilities = cny.filter { $0.balanceMinor < 0 }.reduce(Int64(0)) { workspaceSaturatingSum($0, $1.balanceMinor) }
                if !cny.isEmpty {
                    FinancePanel {
                        VStack(alignment: .leading, spacing: 14) {
                            HStack(spacing: 18) {
                                workspaceMetric("人民币资产", amount: assets)
                                workspaceMetric("人民币负债", amount: liabilities == Int64.min ? .max : -liabilities)
                                workspaceMetric("人民币净资产", amount: workspaceSaturatingSum(assets, liabilities))
                            }
                            if !netWorth.isEmpty {
                                Text("12 周人民币净资产").font(.callout).foregroundStyle(WorkspacePalette.secondary)
                                Chart(netWorth, id: \.asOfEpochMillis) { point in
                                    LineMark(
                                        x: .value("日期", Date(timeIntervalSince1970: Double(point.asOfEpochMillis) / 1_000)),
                                        y: .value("净资产", Double(point.balanceMinor) / 100)
                                    ).foregroundStyle(WorkspacePalette.accent)
                                }
                                .chartYAxis { AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) }
                                .frame(height: 150)
                                .accessibilityLabel("过去 12 周人民币净资产")
                            }
                        }
                    }
                }
                let foreign = Dictionary(grouping: summary.accountBalances.filter { $0.currency != "CNY" }, by: \.currency)
                if !foreign.isEmpty {
                    FinancePanel {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("外币余额").font(.title3.weight(.medium))
                            ForEach(foreign.keys.sorted(), id: \.self) { currency in
                                let entries = foreign[currency, default: []]
                                let value = entries.reduce(Decimal(0)) { $0 + Decimal($1.balanceMinor) / 100 }
                                let missing = entries.reduce(0) { $0 + Int($1.unvaluedCount) }
                                HStack {
                                    Text(currency)
                                    Spacer()
                                    if missing > 0 { Text("\(missing) 笔待补原币金额").foregroundStyle(.orange) }
                                    Text(value.formatted(.number.precision(.fractionLength(2)))).monospacedDigit()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private func exportBook(_ book: WorkspaceBook, state: FinanceWorkspaceState) {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.json]
        panel.nameFieldStringValue = "\(book.name)-FinanceOS"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let input = store.currentSnapshot()
        Task {
            do {
                let text = try await Task.detached(priority: .userInitiated) {
                    let raw = input.toKMP()
                    let scoped = try FinanceWorkspaceAnalytics().scopedSnapshot(
                        state: state, snapshot: raw, bookId: book.id
                    )
                    return FinanceOSShared.FinanceDataJsonCodec().encode(snapshot: scoped)
                }.value
                try text.write(to: url, atomically: true, encoding: .utf8)
                status = "已导出所选账本"
            } catch { status = error.localizedDescription }
        }
    }

    private func exportConfiguration(_ state: FinanceWorkspaceState) {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.json]
        panel.nameFieldStringValue = "FinanceOS-Workspace"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        Task {
            do {
                try await Task.detached(priority: .utility) {
                    let text = try FinanceWorkspaceCodec.shared.encode(state: state)
                    try text.write(to: url, atomically: true, encoding: .utf8)
                }.value
                status = "已导出工作台配置"
            } catch { status = error.localizedDescription }
        }
    }

    private func auditSection(_ state: FinanceWorkspaceState) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Picker("助手视图", selection: $showsRules) {
                Text("对话").tag(false)
                Text("分类规则").tag(true)
            }.pickerStyle(.segmented).frame(width: 210)

            Group {
                if showsRules {
                    rulePanel(state)
                } else {
                    FinanceConversationView(state: state, bookId: book,
                        bookName: state.books.first { $0.id == book }?.name ?? "全账本") {
                            editingTransaction = store.transaction(id: $0)
                        }
                }
            }
            .id(showsRules)
            .transition(reduceMotion ? .identity : FinanceMotion.reveal)
            .animation(reduceMotion ? nil : FinanceMotion.short, value: showsRules)
        }
    }

    private func rulePanel(_ state: FinanceWorkspaceState) -> some View {
        FinancePanel {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("分类规则").font(.title3.weight(.medium))
                    Spacer()
                    Button("新增规则", systemImage: "plus") { form = .rule }
                    Button("预览建议") { previewRules() }
                    if !suggestedRules.isEmpty {
                        Button("应用 \(suggestedRules.count) 条") { applyRules() }
                            .buttonStyle(.borderedProminent)
                    }
                }
                .buttonStyle(.bordered)
                .disabled(working)
                ForEach(state.automation.rules.filter { book == nil || $0.bookId == nil || $0.bookId == book }, id: \.id) { rule in
                    HStack {
                        Text(rule.name)
                        Text(rule.noteContains)
                            .font(.caption).foregroundStyle(WorkspacePalette.secondary)
                        if let category = store.category(id: rule.categoryId) { Text(category.name) }
                        Spacer()
                        Button("编辑") { edit(.rule, id: rule.id) }.buttonStyle(.bordered)
                        Button("移除", role: .destructive) {
                            suggestedRules = []
                            mutate {
                                let automation = try WorkspaceAutomation().removeRule(state: $0.automation, id: rule.id)
                                return FinanceWorkspace().saveAutomation(state: $0, automation: automation)
                            }
                        }.buttonStyle(.bordered)
                    }.font(.callout)
                }
                ForEach(Array(suggestedRules.prefix(12)), id: \.transaction.id) { item in
                    Text("\(workspaceLabel(Transaction(kmp: item.transaction))) → \(store.category(id: item.categoryId)?.name ?? "分类")")
                        .font(.caption).foregroundStyle(WorkspacePalette.secondary)
                }
            }
        }
    }

    private func previewRules() {
        guard !working, let state = workspace.state else { return }
        working = true
        let rows = store.ledger.records
        let categories = store.categories
        let book = book
        Task {
            defer { working = false }
            do {
                let result = try await Task.detached(priority: .userInitiated) {
                    let transactions = rows.map { $0.toKMP() }
                    let included = Set(try FinanceWorkspaceAnalytics().transactionsForBook(
                        state: state, transactions: transactions, bookId: book
                    ).map(\.id))
                    let scoped = transactions.filter { included.contains($0.id) }
                    return WorkspaceAutomation().ruleSuggestions(
                        state: state.automation, bookId: book, transactions: scoped, categories: categories.map { $0.toKMP() }
                    )
                }.value
                if self.book == book {
                    suggestedRules = result
                    status = result.isEmpty ? "暂无规则匹配。" : "匹配 \(result.count) 笔，确认后更新分类。"
                }
            } catch { status = error.localizedDescription }
        }
    }

    private func applyRules() {
        guard !working, !suggestedRules.isEmpty else { return }
        working = true
        let preview = suggestedRules
        Task {
            defer { working = false }
            do {
                let count = try await store.applyWorkspaceRules(preview)
                suggestedRules = []
                status = "已更新 \(count) 笔分类"
            } catch { status = error.localizedDescription }
        }
    }

    private func mutate(_ action: (FinanceWorkspaceState) throws -> FinanceWorkspaceState) {
        do {
            try workspace.update(action)
            status = "已保存"
        } catch { status = error.localizedDescription }
    }

    private func edit(_ kind: WorkspaceForm, id: String) {
        editingEntityId = id
        form = kind
    }
}

private func workspaceSaturatingSum(_ left: Int64, _ right: Int64) -> Int64 {
    let (result, overflow) = left.addingReportingOverflow(right)
    return overflow ? (right < 0 ? .min : .max) : result
}

private func workspaceInboxTitle(_ kind: WorkspaceInboxKind) -> String {
    if kind == .receipt { return "凭证" }
    if kind == .refund { return "退款" }
    if kind == .reimbursement { return "报销或分账" }
    if kind == .warranty { return "保修" }
    if kind == .renewal { return "续费" }
    if kind == .duplicate { return "疑似重复" }
    if kind == .priceIncrease { return "金额上涨" }
    return "待分类"
}

private func workspaceRepeatTitle(_ repeatRule: WorkspaceRepeat) -> String {
    if repeatRule == .weekly { return "每周" }
    if repeatRule == .monthly { return "每月" }
    return "一次"
}

private func workspaceLinkTitle(_ kind: WorkspaceLinkKind) -> String {
    if kind == .transfer { return "转账或还款" }
    if kind == .refund { return "退款" }
    return "重复"
}

private func readWorkspaceAttachment(_ url: URL) throws -> (data: Data, text: String) {
    let accessed = url.startAccessingSecurityScopedResource()
    defer { if accessed { url.stopAccessingSecurityScopedResource() } }
    let type = UTType(filenameExtension: url.pathExtension)
    guard let type, type.conforms(to: .image) || type.conforms(to: .pdf) || type.conforms(to: .plainText) else {
        throw WorkspaceFailure("请使用图片、PDF 或纯文本凭证。")
    }
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    let limit = 12 * 1024 * 1024
    let data = try handle.read(upToCount: limit + 1) ?? Data()
    guard !data.isEmpty, data.count <= limit else { throw WorkspaceFailure("凭证为空或超过 12 MB。") }

    var text = ""
    if type.conforms(to: .image) {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["zh-Hans", "en-US"]
        let handler = VNImageRequestHandler(data: data)
        if (try? handler.perform([request])) != nil {
            text = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
        }
    } else if type.conforms(to: .pdf) {
        text = PDFDocument(data: data)?.string ?? ""
    } else {
        text = String(data: data, encoding: .utf8) ?? ""
    }
    return (data, String(text.prefix(2000)))
}

private struct WorkspaceEditor: View {
    @Environment(FinanceStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    let kind: WorkspaceForm
    let state: FinanceWorkspaceState
    let activeBook: String?
    let editingId: String?
    let commit: ((FinanceWorkspaceState) throws -> FinanceWorkspaceState) throws -> Void

    @State private var name = ""
    @State private var amount = ""
    @State private var currency = "CNY"
    @State private var selectedAccount = ""
    @State private var legacyAccount = ""
    @State private var legacyAccountChoices: [String] = []
    @State private var selectedBook = ""
    @State private var secondBook = ""
    @State private var splitAmount = ""
    @State private var transactionId = ""
    @State private var relatedId = ""
    @State private var search = ""
    @State private var date = Date()
    @State private var direction: TransactionType = .expense
    @State private var linkKind: WorkspaceLinkKind = .duplicate
    @State private var repeatRule: WorkspaceRepeat = .once
    @State private var count = "0"
    @State private var scenario = ""
    @State private var itemKind: WorkspaceInboxKind = .reimbursement
    @State private var hasDueDate = false
    @State private var note = ""
    @State private var categoryId = ""
    @State private var ruleEnabled = true
    @State private var errorMessage = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(editingId != nil && kind != .assignment ? "编辑\(kind.rawValue.replacingOccurrences(of: "新建", with: ""))" : kind.rawValue)
                .font(.title2.weight(.medium))
            ScrollView {
                VStack(alignment: .leading, spacing: 12) { fields }
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !errorMessage.isEmpty {
                Text(errorMessage).font(.callout).foregroundStyle(.orange).textSelection(.enabled)
            }
            HStack {
                Spacer()
                Button("取消") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("保存") { submit() }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 465, height: height)
        .onAppear(perform: fillExisting)
        .onChange(of: transactionId) {
            if kind == .assignment { fillAssignment() }
        }
        .task {
            guard kind == .account, editingId == nil else { return }
            let rows = store.ledger.records
            let registered = Set(state.accounts.map(\.id))
            legacyAccountChoices = await Task.detached(priority: .userInitiated) {
                Set(rows.lazy.filter { !$0.isDeleted }.compactMap(\.accountId))
                    .subtracting(registered).filter { !$0.isEmpty }.sorted()
            }.value
        }
    }

    private var height: CGFloat {
        switch kind {
        case .account, .book, .checkpoint: 300
        case .link, .rule: 335
        case .assignment, .plan, .item: 480
        }
    }

    @ViewBuilder private var fields: some View {
        switch kind {
        case .account:
            field("账户名称", text: $name)
            field("币种（如 CNY、USD）", text: $currency)
            field("期初余额（负数表示欠款）", text: $amount)
            if !legacyAccountChoices.isEmpty {
                Picker("关联已有账户名称", selection: $legacyAccount) {
                    Text("新账户").tag("")
                    ForEach(legacyAccountChoices, id: \.self) { Text($0).tag($0) }
                }
                .onChange(of: legacyAccount) { if name.isEmpty { name = legacyAccount } }
            }
        case .checkpoint:
            accountPicker(required: true)
            DatePicker("对账截至", selection: $date)
            field("对账单余额", text: $amount)
        case .link:
            field("搜索流水的备注或账户", text: $search)
            transactionPicker("源流水", selection: $transactionId)
            transactionPicker("目标流水", selection: $relatedId)
            Picker("关系", selection: $linkKind) {
                Text("重复记录").tag(WorkspaceLinkKind.duplicate)
                Text("转账或还款").tag(WorkspaceLinkKind.transfer)
                Text("退款对应原消费").tag(WorkspaceLinkKind.refund)
            }
            Text("重复记录先选要排除的流水。退款先选退款入账。转账须匹配入账金额，手续费单独记账。")
                .font(.caption).foregroundStyle(WorkspacePalette.secondary)
        case .book:
            field("账本名称（如旅行或家庭）", text: $name)
            field("报告币种", text: $currency)
        case .assignment:
            field("搜索流水的备注或账户", text: $search)
            transactionPicker("流水", selection: $transactionId)
            accountPicker()
            if (state.assignments.first { $0.transactionId == transactionId }?.splits.count ?? 0) > 2 {
                Text("此流水分摊到多个账本，保存会保留分摊金额。")
                    .font(.caption).foregroundStyle(WorkspacePalette.secondary)
            } else {
                bookPicker("账本", selection: $selectedBook)
                if state.books.count > 1 {
                    bookPicker("分摊到账本", selection: $secondBook)
                    if !secondBook.isEmpty { field("分摊人民币金额", text: $splitAmount) }
                }
            }
            field("原币币种（可选）", text: $currency)
            field("原币金额（可选）", text: $amount)
        case .plan:
            field("计划名称", text: $name)
            field("人民币金额", text: $amount)
            Picker("方向", selection: $direction) {
                Text("支出").tag(TransactionType.expense)
                Text("收入").tag(TransactionType.income)
            }
            DatePicker("开始日期", selection: $date, displayedComponents: .date)
            Picker("重复", selection: $repeatRule) {
                Text("仅一次").tag(WorkspaceRepeat.once)
                Text("每周").tag(WorkspaceRepeat.weekly)
                Text("每月").tag(WorkspaceRepeat.monthly)
            }
            if repeatRule != .once { field("期数（0 表示持续）", text: $count) }
            accountPicker()
            bookPicker("账本", selection: $selectedBook)
            field("试算情景（留空为常规计划）", text: $scenario)
        case .item:
            field("待办标题", text: $name)
            Picker("类型", selection: $itemKind) {
                Text("退款").tag(WorkspaceInboxKind.refund)
                Text("报销或分账").tag(WorkspaceInboxKind.reimbursement)
                Text("保修").tag(WorkspaceInboxKind.warranty)
                Text("续费").tag(WorkspaceInboxKind.renewal)
                Text("凭证").tag(WorkspaceInboxKind.receipt)
            }
            bookPicker("账本", selection: $selectedBook)
            field("金额（可选）", text: $amount)
            TextField("说明、报销人或凭证识别结果", text: $note, axis: .vertical)
                .textFieldStyle(.roundedBorder).lineLimit(2...5)
            Toggle("设置到期日", isOn: $hasDueDate)
            if hasDueDate { DatePicker("到期日", selection: $date, displayedComponents: .date) }
            field("搜索关联流水（可选）", text: $search)
            transactionPicker("关联流水", selection: $transactionId)
        case .rule:
            field("规则名称", text: $name)
            field("备注包含（例如商户名称）", text: $note)
            bookPicker("账本范围", selection: $selectedBook)
            Picker("应用分类", selection: $categoryId) {
                Text("选择分类").tag("")
                ForEach(store.categories) { category in Text(category.name).tag(category.id) }
            }
            Toggle("启用规则", isOn: $ruleEnabled)
        }
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        TextField(label, text: text).textFieldStyle(.roundedBorder)
    }

    private func accountPicker(required: Bool = false) -> some View {
        Picker("账户", selection: $selectedAccount) {
            Text(required ? "选择账户" : "未指定").tag("")
            ForEach(state.accounts, id: \.id) { entry in Text(entry.name).tag(entry.id) }
        }
    }

    private func bookPicker(_ title: String, selection: Binding<String>) -> some View {
        Picker(title, selection: selection) {
            Text("未指定").tag("")
            ForEach(state.books, id: \.id) { entry in Text(entry.name).tag(entry.id) }
        }
    }

    private func transactionPicker(_ title: String, selection: Binding<String>) -> some View {
        let keyword = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let retained = Set([transactionId, relatedId].filter { !$0.isEmpty })
        let records = store.ledger.records
        let recent = records.lazy.filter { row in
            !row.isDeleted && (keyword.isEmpty || row.note?.localizedCaseInsensitiveContains(keyword) == true ||
                row.accountId?.localizedCaseInsensitiveContains(keyword) == true || row.id.localizedCaseInsensitiveContains(keyword))
        }.prefix(80)
        let ids = Set(recent.map(\.id))
        let selected = retained.subtracting(ids).compactMap { store.transaction(id: $0) }
        return Picker(title, selection: selection) {
            Text("未选择").tag("")
            ForEach(Array(recent) + selected) { row in Text(workspaceLabel(row)).tag(row.id) }
        }
    }

    private func submit() {
        do {
            try commit(next)
            dismiss()
        } catch { errorMessage = error.localizedDescription }
    }

    private func fillExisting() {
        selectedBook = activeBook ?? ""
        if kind == .checkpoint { selectedAccount = state.accounts.first?.id ?? "" }
        guard let editingId else { return }
        switch kind {
        case .account:
            if let item = state.accounts.first(where: { $0.id == editingId }) {
                name = item.name
                currency = item.currency
                amount = editableMinor(item.openingBalanceMinor)
            }
        case .checkpoint:
            if let item = state.checkpoints.first(where: { $0.id == editingId }) {
                selectedAccount = item.accountId
                date = Date(timeIntervalSince1970: Double(item.asOfEpochMillis) / 1000)
                amount = editableMinor(item.statementBalanceMinor)
            }
        case .book:
            if let item = state.books.first(where: { $0.id == editingId }) {
                name = item.name
                currency = item.currency
            }
        case .assignment:
            transactionId = editingId
            fillAssignment()
        case .plan:
            if let item = state.plans.first(where: { $0.id == editingId }) {
                name = item.name
                amount = editableMinor(item.amountMinor)
                direction = item.type == .income ? .income : .expense
                date = workspaceDate(item.firstEpochDay)
                repeatRule = item.repeat
                count = String(item.occurrences)
                selectedAccount = item.accountId ?? ""
                selectedBook = item.bookId ?? ""
                scenario = item.scenario ?? ""
            }
        case .item:
            if let item = state.automation.items.first(where: { $0.id == editingId }) {
                name = item.title
                itemKind = item.kind
                selectedBook = item.bookId ?? ""
                amount = item.amountMinor > 0 ? editableMinor(item.amountMinor) : ""
                note = item.note
                hasDueDate = item.dueAtEpochMillis > 0
                if hasDueDate { date = Date(timeIntervalSince1970: Double(item.dueAtEpochMillis) / 1000) }
                transactionId = item.transactionIds.first ?? ""
            }
        case .rule:
            if let item = state.automation.rules.first(where: { $0.id == editingId }) {
                name = item.name
                selectedBook = item.bookId ?? ""
                note = item.noteContains
                categoryId = item.categoryId
                ruleEnabled = item.enabled
            }
        case .link: break
        }
    }

    private func fillAssignment() {
        let item = state.assignments.first { $0.transactionId == transactionId }
        selectedAccount = item?.accountId ?? ""
        selectedBook = item?.bookId ?? item?.splits.first?.bookId ?? activeBook ?? ""
        secondBook = ""
        splitAmount = ""
        if let portions = item?.splits, portions.count == 2 {
            secondBook = portions[1].bookId
            splitAmount = editableMinor(portions[1].amountMinor)
        }
        currency = item?.originalCurrency ?? "CNY"
        amount = item?.originalAmountMinor.map { editableMinor($0.int64Value) } ?? ""
    }

    private func editableMinor(_ amount: Int64) -> String {
        NSDecimalNumber(decimal: Decimal(amount) / 100).stringValue
    }

    private func next(_ state: FinanceWorkspaceState) throws -> FinanceWorkspaceState {
        let engine = FinanceWorkspace()
        let id = editingId ?? UUID().uuidString.lowercased()
        let name = trimmed(name)
        let currency = trimmed(currency).uppercased()
        let account = selectedAccount.isEmpty ? nil : selectedAccount
        let book = selectedBook.isEmpty ? nil : selectedBook
        switch kind {
        case .account:
            let entry = WorkspaceAccount(
                id: editingId == nil && !legacyAccount.isEmpty ? legacyAccount : id,
                name: name, currency: currency, openingBalanceMinor: try workspaceMinor(amount, allowNegative: true)
            )
            return try engine.saveAccount(state: state, account: entry)
        case .checkpoint:
            guard let account else { throw WorkspaceFailure("请选择要对账的账户。") }
            let entry = WorkspaceCheckpoint(
                id: id, accountId: account, asOfEpochMillis: date.epochMillis,
                statementBalanceMinor: try workspaceMinor(amount, allowNegative: true)
            )
            return try engine.saveCheckpoint(state: state, checkpoint: entry)
        case .link:
            guard !transactionId.isEmpty, !relatedId.isEmpty else { throw WorkspaceFailure("请选择两笔流水。") }
            let link = WorkspaceLink(sourceId: transactionId, targetId: relatedId, kind: linkKind)
            let transactions = [transactionId, relatedId].compactMap { store.transaction(id: $0)?.toKMP() }
            return try engine.saveLink(state: state, link: link, transactions: transactions)
        case .book:
            return try engine.saveBook(state: state, book: WorkspaceBook(id: id, name: name, currency: currency))
        case .assignment:
            guard let transaction = store.transaction(id: transactionId) else { throw WorkspaceFailure("请选择有效流水。") }
            let previous = state.assignments.first { $0.transactionId == transactionId }?.splits ?? []
            var splits: [WorkspaceSplit] = previous.count > 2 ? previous : []
            if previous.count <= 2, !secondBook.isEmpty {
                let split = try workspaceMinor(splitAmount)
                guard let book, book != secondBook, split < transaction.amount else {
                    throw WorkspaceFailure("请选择两个不同账本，分摊金额须小于流水金额。")
                }
                splits = [WorkspaceSplit(bookId: book, amountMinor: transaction.amount - split),
                          WorkspaceSplit(bookId: secondBook, amountMinor: split)]
            }
            let original = try trimmed(amount).isEmpty ? nil : KotlinLong(value: workspaceMinor(amount))
            let assignment = WorkspaceAssignment(
                transactionId: transaction.id, accountId: account, bookId: splits.isEmpty ? book : nil,
                originalCurrency: original == nil ? nil : currency, originalAmountMinor: original, splits: splits
            )
            return try engine.saveAssignment(state: state, assignment: assignment, transactions: [transaction.toKMP()])
        case .plan:
            let occurrences = repeatRule == .once ? 1 : (Int32(trimmed(count)) ?? -1)
            let plan = WorkspacePlan(
                id: id, name: name, amountMinor: try workspaceMinor(amount),
                type: direction == .income ? .income : .expense, firstEpochDay: workspaceEpochDay(date),
                repeat: repeatRule, occurrences: occurrences, accountId: account, bookId: book,
                scenario: trimmed(scenario).isEmpty ? nil : trimmed(scenario)
            )
            return try engine.savePlan(state: state, plan: plan)
        case .item:
            let previous = state.automation.items.first { $0.id == id }
            let item = try WorkspaceInboxItem(
                id: id, bookId: book, kind: itemKind, title: name, note: trimmed(note),
                dueAtEpochMillis: hasDueDate ? date.epochMillis : 0,
                amountMinor: try trimmed(amount).isEmpty ? 0 : workspaceMinor(amount),
                transactionIds: transactionId.isEmpty ? [] : [transactionId] + (previous?.transactionIds.dropFirst() ?? []).filter { $0 != transactionId },
                attachmentName: previous?.attachmentName, attachmentReference: previous?.attachmentReference,
                completed: previous?.completed ?? false
            )
            let automation = try WorkspaceAutomation().saveItem(state: state.automation, item: item)
            return engine.saveAutomation(state: state, automation: automation)
        case .rule:
            let rule = try WorkspaceRule(id: id, bookId: book, name: name, noteContains: trimmed(note), categoryId: categoryId, enabled: ruleEnabled)
            let automation = try WorkspaceAutomation().saveRule(state: state.automation, rule: rule)
            return engine.saveAutomation(state: state, automation: automation)
        }
    }

    private func trimmed(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

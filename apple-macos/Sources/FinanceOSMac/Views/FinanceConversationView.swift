@preconcurrency import FinanceOSShared
import SwiftUI

@MainActor
@Observable
final class FinanceConversationSession {
    enum Channel: String, CaseIterable {
        case ledger = "账本对话"
        case general = "通用对话"
    }

    struct Turn: Identifiable {
        let id = UUID()
        let question: String
        var ledger: FinanceChatReply?
        var narrative: FinanceAuditNarrative?
        var text: String?
        var error: String?
    }

    var channel = Channel.ledger
    private var ledgerTurns: [Turn] = []
    private var generalTurns: [Turn] = []
    private(set) var revision = 0
    private(set) var pendingId: UUID?
    private var bookId: String?
    private var provider: String?
    @ObservationIgnored private var request: Task<Void, Never>?

    var busy: Bool { pendingId != nil }
    var visible: [Turn] { channel == .ledger ? ledgerTurns : generalTurns }

    func selectBook(_ bookId: String?) {
        guard self.bookId != bookId else { return }
        self.bookId = bookId
        if channel == .ledger { cancel() }
        ledgerTurns.removeAll()
        revision &+= 1
    }

    func selectProvider(_ provider: String) {
        defer { self.provider = provider }
        guard let previous = self.provider, previous != provider else { return }
        cancel()
        generalTurns.removeAll()
        revision &+= 1
    }

    func clear() {
        cancel()
        if channel == .ledger { ledgerTurns.removeAll() } else { generalTurns.removeAll() }
        revision &+= 1
    }

    func cancel() {
        guard let pendingId else { return }
        request?.cancel()
        update(id: pendingId, channel: channel) { $0.error = "已停止" }
        self.pendingId = nil
        request = nil
    }

    func askLedger(
        _ question: String, state: FinanceWorkspaceState, rows: [Transaction], categories: [Category],
        periods: FinanceChatPeriods, range: Range<Int64>, config: AiServiceConfiguration?
    ) {
        guard !busy, channel == .ledger else { return }
        let bookId = bookId
        let previous = ledgerTurns.reversed().compactMap { $0.ledger?.resolvedQuestion }.first
        start(question) { [weak self] id in
            let worker = Task.detached(priority: .userInitiated) {
                try Task.checkCancellation()
                let scoped = try FinanceWorkspaceAnalytics().transactionsForBook(
                    state: state, transactions: rows.map { $0.toKMP() }, bookId: bookId
                )
                try Task.checkCancellation()
                return try FinanceChat().answerInRange(
                    question: question, previousQuestion: previous, transactions: scoped,
                    categories: categories.map { $0.toKMP() }, periods: periods,
                    rangeStartEpochMillis: range.lowerBound, rangeEndEpochMillis: range.upperBound
                )
            }
            let reply = try await withTaskCancellationHandler {
                try await worker.value
            } onCancel: { worker.cancel() }
            try Task.checkCancellation()
            guard let self, self.pendingId == id else { return }
            self.update(id: id, channel: .ledger) { $0.ledger = reply }
            if let config {
                let draft = try FinanceAi(apiProtocol: config.apiProtocol).auditRequest(model: config.model, answer: reply.answer)
                let response = try await AiService(configuration: config).send(draft.body)
                try Task.checkCancellation()
                let narrative = try draft.parse(response: response)
                if self.pendingId == id { self.update(id: id, channel: .ledger) { $0.narrative = narrative } }
            }
        }
    }

    func askGeneral(_ question: String, config: AiServiceConfiguration) {
        guard !busy, channel == .general else { return }
        let history = generalTurns.suffix(5).flatMap { turn -> [FinanceConversationMessage] in
            guard let answer = turn.text else { return [] }
            return [
                FinanceConversationMessage(role: .user, text: turn.question),
                FinanceConversationMessage(role: .assistant, text: answer)
            ]
        } + [FinanceConversationMessage(role: .user, text: question)]
        start(question) { [weak self] id in
            let draft = try FinanceAi(apiProtocol: config.apiProtocol).conversationRequest(model: config.model, messages: history)
            let response = try await AiService(configuration: config).send(draft.body)
            try Task.checkCancellation()
            let answer = try draft.parse(response: response)
            if self?.pendingId == id { self?.update(id: id, channel: .general) { $0.text = answer } }
        }
    }

    private func start(_ question: String, run: @escaping (UUID) async throws -> Void) {
        let turn = Turn(question: question)
        let channel = channel
        if channel == .ledger {
            ledgerTurns = Array((ledgerTurns + [turn]).suffix(18))
        } else {
            generalTurns = Array((generalTurns + [turn]).suffix(18))
        }
        pendingId = turn.id
        revision &+= 1
        request = Task { [weak self] in
            do {
                try await run(turn.id)
            } catch is CancellationError {
                // The action that invalidated the request already updated the visible turn.
            } catch {
                if self?.pendingId == turn.id { self?.update(id: turn.id, channel: channel) { $0.error = error.localizedDescription } }
            }
            if let self, self.pendingId == turn.id {
                self.pendingId = nil
                self.request = nil
            }
        }
    }

    private func update(id: UUID, channel: Channel, _ edit: (inout Turn) -> Void) {
        if channel == .ledger {
            if let index = ledgerTurns.firstIndex(where: { $0.id == id }) { edit(&ledgerTurns[index]) }
        } else {
            if let index = generalTurns.firstIndex(where: { $0.id == id }) { edit(&generalTurns[index]) }
        }
        revision &+= 1
    }
}

struct FinanceConversationView: View {
    @Environment(FinanceStore.self) private var store
    @Environment(AppRouter.self) private var router
    @Environment(MacFinanceWorkspace.self) private var workspace
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage("ai.endpoint") private var endpoint = ""
    @AppStorage("ai.model") private var model = ""
    @State private var input = ""
    @State private var localError: String?
    @State private var days = 0
    @State private var enhance = false
    @FocusState private var composerFocused: Bool

    let state: FinanceWorkspaceState
    let bookId: String?
    let bookName: String
    let showSource: (String) -> Void

    private var session: FinanceConversationSession { workspace.conversation }
    private var configured: Bool { !endpoint.isEmpty && !model.isEmpty }

    var body: some View {
        @Bindable var session = session
        FinancePanel {
            VStack(alignment: .leading, spacing: 15) {
                HStack(alignment: .center) {
                    Picker("对话类型", selection: $session.channel) {
                        ForEach(FinanceConversationSession.Channel.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 200)
                    .disabled(session.busy)
                    Spacer()
                    Button("AI 设置", systemImage: "gearshape") { router.section = .ai }
                        .disabled(session.busy)
                    Button("清空") { session.clear(); localError = nil }
                        .disabled(session.visible.isEmpty)
                }
                .buttonStyle(.bordered)

                if session.channel == .ledger {
                    HStack {
                        Text(bookName).font(.callout.weight(.medium)).lineLimit(1)
                        Picker("时段", selection: $days) {
                            Text("本月").tag(0)
                            Text("近 30 天").tag(30)
                            Text("近 90 天").tag(90)
                        }
                        .pickerStyle(.segmented)
                        .frame(width: 228)
                        Spacer()
                        Toggle("用 AI 概括", isOn: $enhance).toggleStyle(.checkbox)
                            .disabled(!configured)
                    }
                    .disabled(session.busy)
                }
                Text(session.channel == .ledger ? "在本机计算账本答案。开启 AI 概括时，仅发送汇总指标。"
                    : "将这段通用对话的近期消息发送给所配置的服务。请勿输入账号或卡号。")
                    .font(.caption).foregroundStyle(WorkspacePalette.secondary)

                transcript
                Divider()
                HStack(alignment: .bottom, spacing: 10) {
                    TextField(session.channel == .ledger ? "问问这个账本" : "输入日常财务问题", text: $input, axis: .vertical)
                        .lineLimit(1...4)
                        .textFieldStyle(.roundedBorder)
                        .focused($composerFocused)
                        .onSubmit { send() }
                    if session.busy {
                        Button("停止", systemImage: "stop.fill") { session.cancel() }.buttonStyle(.bordered)
                    } else {
                        Button("发送", systemImage: "arrow.up") { send() }
                            .buttonStyle(.borderedProminent)
                            .keyboardShortcut(.return, modifiers: .command)
                            .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                if let localError { Text(localError).font(.caption).foregroundStyle(WorkspacePalette.negative) }
            }
        }
        .onChange(of: bookId, initial: true) { session.selectBook(bookId) }
        .onChange(of: [endpoint, model], initial: true) { session.selectProvider(endpoint + "\n" + model) }
        .onChange(of: session.channel) { input = ""; localError = nil }
    }

    private var transcript: some View {
        Group {
            if session.visible.isEmpty {
                suggestions.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 16) {
                            ForEach(session.visible) { turn in message(turn).id(turn.id) }
                        }.padding(3)
                    }
                    .defaultScrollAnchor(.bottom)
                    .onChange(of: session.revision) {
                        if let last = session.visible.last {
                            withAnimation(reduceMotion ? nil : FinanceMotion.short) { proxy.scrollTo(last.id, anchor: .bottom) }
                        }
                    }
                }
            }
        }
        .frame(height: 360)
    }

    private var suggestions: some View {
        VStack(spacing: 14) {
            FinanceGlyph.chat.stroke(style: StrokeStyle(lineWidth: 1.2, lineJoin: .round))
                .frame(width: 32, height: 32)
                .foregroundStyle(WorkspacePalette.accent)
                .accessibilityHidden(true)
            HStack {
                let examples = session.channel == .ledger
                    ? ["本月餐饮花了多少？", "哪几笔支出金额最高？", "有没有疑似重复扣款？"]
                    : ["如何分配每月预算？", "怎样整理订阅支出？"]
                ForEach(examples, id: \.self) { example in
                    Button(example) { input = example; composerFocused = true }.buttonStyle(.bordered)
                }
            }
        }
    }

    private func message(_ turn: FinanceConversationSession.Turn) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Spacer(minLength: 42)
                Text(verbatim: turn.question).textSelection(.enabled)
                    .padding(.vertical, 8).padding(.horizontal, 12)
                    .background(WorkspacePalette.raised, in: .rect(cornerRadius: 8))
                    .accessibilityLabel("你：\(turn.question)")
            }
            if let reply = turn.ledger { ledgerAnswer(reply.answer, narrative: turn.narrative) }
            if let text = turn.text {
                Text(verbatim: text).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
            }
            if session.pendingId == turn.id {
                FinanceLoading(label: turn.ledger == nil ? "正在回答" : "正在生成概括")
            }
            if let error = turn.error {
                HStack {
                    Text(error).font(.caption).foregroundStyle(WorkspacePalette.negative)
                    Button("重新提问") { input = turn.question; composerFocused = true }.buttonStyle(.link)
                }
            }
        }
        .padding(.bottom, 4)
    }

    private func ledgerAnswer(_ result: FinanceAuditAnswer, narrative: FinanceAuditNarrative?) -> some View {
        let references = Dictionary(uniqueKeysWithValues: result.sources.map { ($0.reference, $0.transaction.id) })
        let start = Date(timeIntervalSince1970: Double(result.startEpochMillis) / 1_000)
        let end = Date(timeIntervalSince1970: Double(result.endEpochMillis - 1) / 1_000)
        return VStack(alignment: .leading, spacing: 6) {
            Text(result.title).font(.headline)
            Text("\(start.formatted(.dateTime.month().day())) 至 \(end.formatted(.dateTime.month().day()))")
                .font(.caption).foregroundStyle(WorkspacePalette.secondary)
            ForEach(result.facts, id: \.id) { fact in
                HStack(spacing: 7) {
                    Text(fact.text).textSelection(.enabled)
                    if let ref = fact.sourceReferences.first, let id = references[ref] {
                        Button("来源 \(ref)") { showSource(id) }.buttonStyle(.link).controlSize(.small)
                    }
                }
            }
            if let narrative {
                let highlights = narrative.highlights.map(\.text).joined(separator: " · ")
                Text("AI 概括 · \(highlights)").foregroundStyle(WorkspacePalette.accent)
                if !narrative.followUp.isEmpty {
                    Button(narrative.followUp) { input = narrative.followUp; composerFocused = true }
                        .buttonStyle(.link)
                }
            }
            if !result.sources.isEmpty {
                DisclosureGroup("查看流水证据 · \(result.matchedCount) 笔") {
                    ForEach(result.sources, id: \.reference) { source in
                        let row = Transaction(kmp: source.transaction)
                        let date = row.dateTime.formatted(.dateTime.month().day())
                        Button("\(source.reference) · \(date) · \(formatMoney(row.amount))") { showSource(row.id) }
                            .buttonStyle(.link)
                    }
                    if result.omittedSourceCount > 0 {
                        Text("另有 \(result.omittedSourceCount) 笔未列出").foregroundStyle(WorkspacePalette.secondary)
                    }
                }
            }
        }
        .font(.callout)
    }

    private func send() {
        let question = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !question.isEmpty, !session.busy else { return }
        guard question.utf16.count <= (session.channel == .ledger ? 400 : 2_000) else {
            localError = session.channel == .ledger ? "账本问题最多 400 字符。" : "每条消息最多 2,000 字符。"
            return
        }
        do {
            switch session.channel {
            case .ledger:
                let connection = enhance && configured ? try AiServiceConfiguration(baseURL: endpoint, model: model) : nil
                let (periods, range) = queryPeriods()
                session.askLedger(question, state: state, rows: store.ledger.records, categories: store.categories,
                                  periods: periods, range: range, config: connection)
            case .general:
                guard configured else { localError = "请先在 AI 设置中填写接口和模型。"; return }
                try session.askGeneral(question, config: AiServiceConfiguration(baseURL: endpoint, model: model))
            }
            input = ""
            localError = nil
        } catch { localError = error.localizedDescription }
    }

    private func queryPeriods() -> (FinanceChatPeriods, Range<Int64>) {
        let calendar = store.calendar
        let current = Date()
        let today = calendar.startOfDay(for: current)
        let monthStart = calendar.dateInterval(of: .month, for: current)?.start ?? today
        let start = days == 0 ? monthStart : calendar.date(byAdding: .day, value: 1 - days, to: today) ?? today
        let end = calendar.date(byAdding: .day, value: 1, to: today) ?? current
        let previousStart = calendar.date(byAdding: .month, value: -1, to: monthStart) ?? monthStart
        let periods = FinanceChatPeriods(
            currentStartEpochMillis: monthStart.epochMillis, currentEndEpochMillis: end.epochMillis,
            previousStartEpochMillis: previousStart.epochMillis, previousEndEpochMillis: monthStart.epochMillis
        )
        return (periods, start.epochMillis..<end.epochMillis)
    }
}

@preconcurrency import FinanceOSShared
import Security
import SwiftUI

private struct AiServiceFailure: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

struct AiServiceConfiguration {
    static let defaultEndpoint = "https://api.openai.com/v1"
    let endpoint: URL
    let model: String
    let apiProtocol: AiApiProtocol

    init(baseURL: String, model: String) throws {
        let address = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = model.trimmingCharacters(in: .whitespacesAndNewlines)
        guard address.utf8.count <= 2_048, var parts = URLComponents(string: address),
              parts.scheme?.lowercased() == "https",
              let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.port.map({ (1...65535).contains($0) }) ?? true else {
            throw AiServiceFailure("请输入有效的 HTTPS API 地址。")
        }
        guard !model.isEmpty && model.count <= 160,
              !model.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else {
            throw AiServiceFailure("请输入模型名称。")
        }
        parts.scheme = "https"
        parts.host = host.lowercased()
        if parts.port == 443 { parts.port = nil }
        let path = parts.percentEncodedPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let suffix = "/\(path)"
        if suffix.hasSuffix("/chat/completions") {
            apiProtocol = .chatCompletions
            parts.percentEncodedPath = suffix
        } else {
            apiProtocol = .responses
            if path.isEmpty {
                parts.percentEncodedPath = "/v1/responses"
            } else {
                parts.percentEncodedPath = suffix.hasSuffix("/responses") ? suffix : "\(suffix)/responses"
            }
        }
        guard let endpoint = parts.url else { throw AiServiceFailure("API 地址不可用。") }
        self.endpoint = endpoint
        self.model = model
    }
}

private enum AiKeychain {
    private static func query(_ endpoint: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.financeos.mac.ai",
            kSecAttrAccount as String: endpoint,
        ]
    }

    static func read(_ endpoint: String) throws -> String? {
        var attributes = query(endpoint)
        attributes[kSecReturnData as String] = true
        attributes[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(attributes as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data,
              let key = String(data: data, encoding: .utf8) else {
            throw AiServiceFailure("无法读取钥匙串中的 API 密钥（\(status)）。")
        }
        return key
    }

    static func save(_ key: String, for endpoint: String) throws {
        let attributes = query(endpoint)
        let data = Data(key.utf8)
        var status = SecItemUpdate(attributes as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var newItem = attributes
            newItem[kSecValueData as String] = data
            status = SecItemAdd(newItem as CFDictionary, nil)
        }
        guard status == errSecSuccess else {
            throw AiServiceFailure("无法保存 API 密钥到钥匙串（\(status)）。")
        }
    }

    static func remove(_ endpoint: String) throws {
        let status = SecItemDelete(query(endpoint) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw AiServiceFailure("无法删除钥匙串中的 API 密钥（\(status)）。")
        }
    }
}

private final class AiNoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

final class AiService {
    private let endpoint: URL
    private let key: String?
    private let session: URLSession

    init(configuration: AiServiceConfiguration) throws {
        endpoint = configuration.endpoint
        key = try AiKeychain.read(configuration.endpoint.absoluteString)
        let settings = URLSessionConfiguration.ephemeral
        settings.timeoutIntervalForRequest = 30
        settings.timeoutIntervalForResource = 60
        settings.httpShouldSetCookies = false
        session = URLSession(configuration: settings, delegate: AiNoRedirects(), delegateQueue: nil)
    }

    deinit { session.invalidateAndCancel() }

    func send(_ body: String) async throws -> String {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let key { request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization") }
        request.httpBody = Data(body.utf8)

        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw AiServiceFailure("AI 服务没有返回 HTTP 响应。") }
        switch http.statusCode {
        case 200..<300: break
        case 401, 403: throw AiServiceFailure("AI 服务拒绝访问，请核对密钥和模型权限。")
        case 404: throw AiServiceFailure("服务未找到此接口，请核对 API 地址和模型。")
        case 429: throw AiServiceFailure("AI 服务请求过多或额度不足，请稍后再试。")
        case 300..<400: throw AiServiceFailure("API 地址发生重定向，请填写服务商的最终 HTTPS 地址。")
        default: throw AiServiceFailure("AI 服务返回 HTTP \(http.statusCode)。")
        }
        let maximum = 256_000
        guard response.expectedContentLength <= maximum else { throw AiServiceFailure("AI 响应过大。") }
        var data = Data()
        for try await byte in bytes {
            guard data.count < maximum else { throw AiServiceFailure("AI 响应过大。") }
            data.append(byte)
        }
        guard let text = String(data: data, encoding: .utf8) else { throw AiServiceFailure("AI 响应编码无效。") }
        return text
    }
}

private struct AiReviewItem: Identifiable {
    let suggestion: AiCategorySuggestion
    let original: Transaction
    var id: String { original.id }
}

struct AiInsightsView: View {
    @Environment(FinanceStore.self) private var store
    @Environment(AppRouter.self) private var router
    @AppStorage("ai.endpoint") private var savedEndpoint = ""
    @AppStorage("ai.model") private var savedModel = ""
    @State private var endpoint = AiServiceConfiguration.defaultEndpoint
    @State private var model = ""
    @State private var key = ""
    @State private var status = ""
    @State private var month = BudgetMonth(
        year: Calendar.current.component(.year, from: Date()),
        month: Calendar.current.component(.month, from: Date())
    )
    @State private var suggestions: [AiReviewItem] = []
    @State private var selections: [String: String] = [:]
    @State private var creatingCategoryFor: AiReviewItem?
    @State private var requestDetail: String?
    @State private var processed = 0
    @State private var planned = 0
    @State private var advice: AiSpendingAdvice?
    @State private var request: Task<Void, Never>?
    private var currentMonth: BudgetMonth {
        let parts = store.calendar.dateComponents([.year, .month], from: Date())
        return BudgetMonth(year: parts.year ?? 1, month: parts.month ?? 1)
    }
    @State private var applying = false

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 16) {
                configurationCard
                taskCard
                if !status.isEmpty { Text(status).font(.callout).foregroundStyle(.secondary) }
                if !suggestions.isEmpty { suggestionCard }
                if let advice { adviceCard(advice) }
            }
            .padding(20)
        }
        .navigationTitle("AI 账单助手")
        .toolbar { Button("打开对话", systemImage: "bubble.left.and.bubble.right") { router.openWorkspaceChat() } }
        .onAppear {
            endpoint = savedEndpoint.isEmpty ? AiServiceConfiguration.defaultEndpoint : savedEndpoint
            model = savedModel
        }
        .onDisappear { request?.cancel() }
        .sheet(item: $creatingCategoryFor) { item in
            AiNewCategorySheet(type: item.original.type) { name in
                let updated = try await store.createAiCategory(item.original, name: name)
                finishReview(item, updated: updated)
            }
        }
    }

    private var configurationCard: some View {
        FinancePanel {
            VStack(alignment: .leading, spacing: 10) {
                Label("AI 服务设置", systemImage: "gearshape").font(.headline)
                TextField("HTTPS 服务地址", text: $endpoint)
                Text("填写服务商的 API 基地址或完整 Responses 地址。Chat Completions 服务填完整 /chat/completions 地址。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("模型名称", text: $model)
                SecureField("API 密钥（可选，留空时使用此地址已保存的密钥）", text: $key)
                HStack {
                    Button("保存设置", action: saveConfiguration)
                    Button("清除设置与密钥", action: clearConfiguration).disabled(savedEndpoint.isEmpty)
                }
                .buttonStyle(.bordered)
                .disabled(request != nil)
            }
        }
    }

    private var taskCard: some View {
        FinancePanel {
            VStack(alignment: .leading, spacing: 12) {
                Label("分析账单", systemImage: "sparkles").font(.headline)
                if !savedEndpoint.isEmpty {
                    Text(savedEndpoint).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                }
                Text("每次最多分析所选月份 200 笔含备注的「其他」流水，每批 20 笔。分类请求发送脱敏备注、收支方向和分类名称。月度建议只发送汇总金额、分类名和预算。")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                HStack {
                    Button("上月", systemImage: "chevron.left") { changeMonth(to: month.previous()) }
                        .labelStyle(.iconOnly)
                    Text("\(month.year) 年 \(month.month) 月").monospacedDigit()
                    Button("下月", systemImage: "chevron.right") { changeMonth(to: month.next()) }
                        .labelStyle(.iconOnly)
                        .disabled(month >= currentMonth)
                    Spacer()
                    Button("生成分类建议") { analyze(classification: true) }
                    Button("分析月度消费") { analyze(classification: false) }
                }
                .buttonStyle(.bordered)
                .disabled(savedEndpoint.isEmpty || request != nil || applying)
                if let request {
                    HStack {
                        FinanceLoading(compact: true)
                        if let requestDetail {
                            Text(requestDetail).font(.caption).foregroundStyle(.secondary)
                        }
                        Button("取消请求") { request.cancel() }
                    }
                }
            }
        }
    }

    private var suggestionCard: some View {
        FinancePanel {
            LazyVStack(alignment: .leading, spacing: 12) {
                Label("待确认分类 · \(suggestions.count)", systemImage: "checklist").font(.headline)
                ForEach(suggestions) { item in reviewRow(item) }
            }
        }
    }

    private func reviewRow(_ item: AiReviewItem) -> some View {
        let transaction = item.original
        let suggestion = item.suggestion
        let current = store.transaction(id: item.id) == transaction
        let available = store.categories(for: transaction.type)
        let chosenId = selections[item.id] ?? suggestion.categoryId
        let selection = available.first { $0.id == chosenId }
        let suggestedName = store.category(id: suggestion.categoryId)?.name
        return HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 4) {
                Text(transaction.note ?? "流水").lineLimit(2)
                Text(transaction.dateTime.formatted(date: .abbreviated, time: .omitted))
                    .font(.caption).foregroundStyle(.secondary)
                if current, let suggestedName {
                    Text(suggestion.reason.isEmpty ? "建议 \(suggestedName)" : "建议 \(suggestedName) · \(suggestion.reason)")
                        .font(.caption).foregroundStyle(.secondary)
                } else if !current {
                    Text("流水已变更").font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            Text(formatMoney(transaction.amount)).monospacedDigit()
            if current {
                Menu {
                    ForEach(available) { category in
                        Button {
                            selections[item.id] = category.id
                        } label: {
                            if selection?.id == category.id {
                                Label(category.name, systemImage: "checkmark")
                            } else {
                                Text(category.name)
                            }
                        }
                    }
                    Divider()
                    Button("新建分类…", systemImage: "plus") { creatingCategoryFor = item }
                } label: {
                    Text(selection?.name ?? "选择分类").lineLimit(1)
                }
                .frame(maxWidth: 160)
                .accessibilityLabel("分类")
                .disabled(applying || request != nil)
                Button("应用") {
                    if let selection { accept(item, category: selection) }
                }
                .disabled(selection == nil || applying || request != nil)
            }
            Button(current ? "跳过" : "移除") { dismiss(item) }
                .disabled(applying || request != nil)
        }
    }

    private func adviceCard(_ advice: AiSpendingAdvice) -> some View {
        FinancePanel {
            VStack(alignment: .leading, spacing: 10) {
                Label("月度消费建议", systemImage: "chart.pie").font(.headline)
                Text(advice.summary)
                ForEach(advice.recommendations, id: \.self) { recommendation in
                    Label(recommendation, systemImage: "circle.fill").font(.callout)
                }
            }
            .textSelection(.enabled)
        }
    }

    private func saveConfiguration() {
        do {
            let config = try AiServiceConfiguration(baseURL: endpoint, model: model)
            let address = config.endpoint.absoluteString
            let credential = key.trimmingCharacters(in: .whitespacesAndNewlines)
            if !credential.isEmpty {
                guard credential.utf8.count <= 4_096, credential.utf8.allSatisfy({ (33...126).contains($0) }) else {
                    throw AiServiceFailure("API 密钥包含无效字符。")
                }
                try AiKeychain.save(credential, for: address)
            }
            if !savedEndpoint.isEmpty && savedEndpoint != address {
                try AiKeychain.remove(savedEndpoint)
            }
            savedEndpoint = address
            endpoint = address
            savedModel = config.model
            key = ""
            suggestions = []
            selections = [:]
            advice = nil
            status = "AI 服务设置已保存。"
        } catch { status = error.localizedDescription }
    }

    private func clearConfiguration() {
        do {
            try AiKeychain.remove(savedEndpoint)
            savedEndpoint = ""
            savedModel = ""
            endpoint = AiServiceConfiguration.defaultEndpoint
            model = ""
            key = ""
            suggestions = []
            selections = [:]
            advice = nil
            status = "已清除 AI 服务设置。"
        } catch { status = error.localizedDescription }
    }

    private func analyze(classification: Bool) {
        guard request == nil, !applying else { return }
        status = ""
        processed = 0
        planned = 0
        requestDetail = nil
        if classification {
            suggestions = []
            selections = [:]
        } else {
            advice = nil
        }

        request = Task {
            defer {
                request = nil
                requestDetail = nil
            }
            do {
                let configuration = try AiServiceConfiguration(baseURL: savedEndpoint, model: savedModel)
                let ai = FinanceAi(apiProtocol: configuration.apiProtocol)
                let period = month.period(calendar: store.calendar)
                if classification {
                    try await classify(ai: ai, configuration: configuration, period: period)
                } else {
                    try await analyzeSpending(ai: ai, configuration: configuration, period: period)
                }
            } catch {
                let message = Task.isCancelled ? "请求已取消。" : error.localizedDescription
                status = classification && processed > 0
                    ? "已分析 \(processed)/\(planned) 笔，保留 \(suggestions.count) 条建议。\(message)"
                    : message
            }
        }
    }

    private func analyzeSpending(
        ai: FinanceAi, configuration: AiServiceConfiguration, period: MonthPeriod
    ) async throws {
        let body = try ai.spendingRequest(
            model: configuration.model,
            transactions: store.monthlyTransactions(in: period).map { $0.toKMP() },
            categories: store.categories.map { $0.toKMP() },
            period: FinanceOSShared.MonthPeriod(
                month: KotlinBudgetMonth(year: Int32(month.year), month: Int32(month.month)),
                startInclusive: KMPBridge.instant(from: period.startInclusive),
                endExclusive: KMPBridge.instant(from: period.endExclusive)
            ),
            budgets: store.budgets.filter { $0.month == month }.map { $0.toKMP() }
        )
        let response = try await AiService(configuration: configuration).send(body)
        try Task.checkCancellation()
        advice = try ai.parseSpending(response: response)
    }

    private func classify(ai: FinanceAi, configuration: AiServiceConfiguration, period: MonthPeriod) async throws {
        let rows = store.transactionSlice(in: period)
        let categories = store.categories
        let (plans, originals) = try await Task.detached(priority: .userInitiated) {
            let eligible = rows.filter { !$0.isDeleted && $0.categoryId == "system-other" }
            let requests = try ai.classificationRequests(
                model: configuration.model,
                transactions: eligible.map { $0.toKMP() },
                categories: categories.map { $0.toKMP() }
            )
            let byId = Dictionary(eligible.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            return (requests, byId)
        }.value
        try Task.checkCancellation()
        planned = plans.reduce(0) { $0 + Int($1.transactionCount) }
        let service = try AiService(configuration: configuration)
        for (index, plan) in plans.enumerated() {
            try Task.checkCancellation()
            requestDetail = "第 \(index + 1)/\(plans.count) 批 · 已分析 \(processed)/\(planned) 笔"
            let response = try await service.send(plan.body)
            try Task.checkCancellation()
            let received = try plan.parse(response: response)
            suggestions.append(contentsOf: received.compactMap { suggestion in
                originals[suggestion.transactionId].map { AiReviewItem(suggestion: suggestion, original: $0) }
            })
            processed += Int(plan.transactionCount)
        }
        status = "已分析 \(processed) 笔流水，收到 \(suggestions.count) 条分类建议。"
    }

    private func changeMonth(to month: BudgetMonth) {
        self.month = month
        suggestions = []
        selections = [:]
        advice = nil
        status = ""
    }

    private func dismiss(_ item: AiReviewItem) {
        suggestions.removeAll { $0.id == item.id }
        selections[item.id] = nil
    }

    private func accept(_ item: AiReviewItem, category: Category) {
        guard !applying else { return }
        if category.id == item.original.categoryId {
            dismiss(item)
            status = "已保留「\(category.name)」。"
            return
        }
        applying = true
        Task {
            defer { applying = false }
            do {
                let updated = try await store.applyAiCategory(item.original, categoryId: category.id)
                finishReview(item, updated: updated)
            } catch { status = error.localizedDescription }
        }
    }

    private func finishReview(_ item: AiReviewItem, updated: Bool) {
        if updated || store.transaction(id: item.id) != item.original { dismiss(item) }
        status = updated ? "分类已更新。" : "流水或目标分类已变更。"
    }
}

private struct AiNewCategorySheet: View {
    @Environment(\.dismiss) private var dismiss
    let type: TransactionType
    let apply: (String) async throws -> Void
    @State private var name = ""
    @State private var errorMessage = ""
    @State private var saving = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("新建并应用分类").font(.headline)
            TextField(type == .income ? "收入分类名称" : "支出分类名称", text: $name)
                .textFieldStyle(.roundedBorder)
                .disabled(saving)
            if !errorMessage.isEmpty {
                Text(errorMessage).font(.callout).foregroundStyle(.red)
            }
            HStack {
                Spacer()
                Button("取消") { dismiss() }.keyboardShortcut(.cancelAction).disabled(saving)
                Button("新建并应用") { submit() }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .disabled(saving || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(20)
        .frame(width: 380)
        .interactiveDismissDisabled(saving)
    }

    private func submit() {
        saving = true
        errorMessage = ""
        Task {
            defer { saving = false }
            do {
                try await apply(name)
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}

import SwiftUI
import Network
import Observation

/// 局域网手动共享（iOS）：与 macOS / Android 同一明文 HTTP 协议。
/// GET /api/ping 探活；GET /api/snapshot 拉取对方快照；POST /api/snapshot 推送合并导入。
struct LanShareView: View {
    @Environment(FinanceStore.self) private var store

    var body: some View {
        LanShareContentView(store: store)
    }
}

private struct LanShareContentView: View {
    let store: FinanceStore
    @State private var model: LanShareModel

    init(store: FinanceStore) {
        self.store = store
        _model = State(initialValue: LanShareModel(store: store))
    }

    var body: some View {
        List {
            Section("接收（作为服务器）") {
                HStack {
                    TextField("端口", text: $model.portText)
#if os(iOS)
                        .keyboardType(.numberPad)
#endif
                        .disabled(model.isServerRunning)
                    Button(model.isServerRunning ? "停止接收" : "开始接收") {
                        if model.isServerRunning {
                            model.stopServer()
                        } else {
                            model.startServer()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(model.isServerRunning ? .red : .accentColor)
                }
                if model.isServerRunning {
                    ForEach(model.serverURLs, id: \.self) { url in
                        HStack {
                            Text(url).font(.callout.monospaced()).textSelection(.enabled)
                            Spacer()
#if os(iOS)
                            Button("复制") {
                                UIPasteboard.general.string = url
                            }
                            .font(.caption)
#else
                            Text("")
#endif
                        }
                    }
                }
            }

            Section("发送（作为客户端）") {
                TextField("对端地址，如 192.168.1.8", text: $model.host)
#if os(iOS)
                    .keyboardType(.numbersAndPunctuation)
                    .textInputAutocapitalization(.never)
#endif
                    .autocorrectionDisabled()
                Button {
                    Task { await model.pull() }
                } label: {
                    Label("拉取对方快照", systemImage: "arrow.down.doc")
                }
                .disabled(model.isBusy)
                Button {
                    Task { await model.push() }
                } label: {
                    Label("把我的快照推送给对方", systemImage: "arrow.up.doc")
                }
                .disabled(model.isBusy)
            }

            if !model.log.isEmpty {
                Section("日志") {
                    Text(model.log)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                }
            }
        }
        .navigationTitle("局域网共享")
#if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
#endif
    }
}

@MainActor
@Observable
final class LanShareModel {
    var portText = "45678"
    var host = ""
    var isServerRunning = false
    var localAddresses: [String] = []
    var log = ""
    var isBusy = false

    private var listener: NWListener?
    private let store: FinanceStore

    init(store: FinanceStore) {
        self.store = store
    }

    var serverURLs: [String] {
        localAddresses.map { "http://\($0):\(portText)" }
    }

    func startServer() {
        guard !isServerRunning, let port = NWEndpoint.Port(portText) else { return }
        do {
            let listener = try NWListener(using: .tcp, on: port)
            listener.newConnectionHandler = { [weak self] connection in
                connection.start(queue: .global(qos: .userInitiated))
                Task { @MainActor in
                    self?.handle(connection)
                }
            }
            listener.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in
                    guard let self else { return }
                    switch state {
                    case .ready:
                        self.isServerRunning = true
                        self.localAddresses = Self.localIPv4Addresses()
                        self.append("服务已启动：端口 \(port.rawValue)")
                    case .failed(let error):
                        self.append("服务启动失败：\(error.localizedDescription)")
                        self.isServerRunning = false
                    default:
                        break
                    }
                }
            }
            listener.start(queue: .global(qos: .userInitiated))
            self.listener = listener
        } catch {
            append("无法监听端口：\(error.localizedDescription)")
        }
    }

    func stopServer() {
        listener?.cancel()
        listener = nil
        isServerRunning = false
        append("服务已停止")
    }

    func pull() async {
        await transfer(title: "拉取对方快照") { url in
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                throw LanError("拉取失败：对方返回异常状态")
            }
            let content = String(decoding: data, as: UTF8.self)
            let result = try self.store.importJSON(content)
            return "拉取完成：新增 \(result.transactionCount) 笔流水、\(result.categoryCount) 个分类、\(result.budgetCount) 条预算"
        }
    }

    func push() async {
        await transfer(title: "推送我的快照") { url in
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            let body = try self.store.exportJSON()
            request.httpBody = Data(body.utf8)
            request.timeoutInterval = 15
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw LanError("推送失败：无响应") }
            if http.statusCode != 200 {
                throw LanError("推送失败：对方返回 \(http.statusCode)")
            }
            _ = data
            return "推送完成，对方已合并导入"
        }
    }

    private func transfer(title: String, operation: (URL) async throws -> String) async {
        guard !isBusy else { return }
        guard !host.isEmpty else {
            append("请先填写对端主机地址")
            return
        }
        let urlText = host.hasPrefix("http") ? host : "http://\(host):\(portText)"
        guard let url = URL(string: urlText.hasSuffix("/api/snapshot") ? urlText : urlText + "/api/snapshot") else {
            append("主机地址无效：\(urlText)")
            return
        }
        isBusy = true
        append("\(title)… \(url.absoluteString)")
        do {
            let message = try await operation(url)
            append(message)
        } catch {
            append("失败：\(error.localizedDescription)")
        }
        isBusy = false
    }

    private func handle(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4 * 1024 * 1024) { [weak self] data, _, isComplete, error in
            Task { @MainActor in
                guard let self else {
                    connection.cancel()
                    return
                }
                guard let data, !data.isEmpty else {
                    connection.cancel()
                    return
                }
                self.process(data, on: connection)
                if isComplete || error != nil {
                    connection.cancel()
                } else {
                    self.handle(connection)
                }
            }
        }
    }

    private func process(_ data: Data, on connection: NWConnection) {
        let text = String(decoding: data, as: UTF8.self)
        let lines = text.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return }
        let method = String(parts[0])
        let path = String(parts[1])

        var body = ""
        if let bodyIndex = text.range(of: "\r\n\r\n") {
            body = String(text[bodyIndex.upperBound...])
        }

        var status = "404 Not Found"
        var contentType = "text/plain"
        var payload = "not found"

        if path == "/api/ping" {
            status = "200 OK"
            contentType = "application/json; charset=utf-8"
            payload = "{\"status\":\"ok\",\"device\":\"FinanceOSiOS\"}"
        } else if path == "/api/snapshot" {
            switch method {
            case "GET":
                if let json = try? store.exportJSON() {
                    status = "200 OK"
                    contentType = "application/json; charset=utf-8"
                    payload = json
                } else {
                    status = "500 Internal Server Error"
                    payload = "{\"error\":\"导出失败\"}"
                }
            case "POST":
                do {
                    let result = try store.importJSON(body)
                    status = "200 OK"
                    contentType = "application/json; charset=utf-8"
                    payload = "{\"imported\":{\"transactions\":\(result.transactionCount),\"categories\":\(result.categoryCount),\"budgets\":\(result.budgetCount)}}"
                } catch {
                    status = "400 Bad Request"
                    contentType = "application/json; charset=utf-8"
                    payload = "{\"error\":\"导入失败\"}"
                }
            default:
                break
            }
        }

        let response = "HTTP/1.1 \(status)\r\nContent-Type: \(contentType)\r\nContent-Length: \(payload.utf8.count)\r\nConnection: close\r\n\r\n\(payload)"
        connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func append(_ text: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let line = "[\(formatter.string(from: Date()))] \(text)"
        if log.count > 4000 {
            log = String(log.suffix(3000))
        }
        log = log.isEmpty ? line : log + "\n" + line
    }

    /// 遍历网卡取局域网 IPv4（en0/en1），供对端填写。
    static func localIPv4Addresses() -> [String] {
        var result: [String] = []
        var address: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&address) == 0, let start = address else { return result }
        defer { freeifaddrs(address) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = start
        while let current = cursor {
            let interface = current.pointee
            if interface.ifa_addr != nil, interface.ifa_addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                if name.hasPrefix("en") {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(
                        interface.ifa_addr,
                        socklen_t(interface.ifa_addr.pointee.sa_len),
                        &hostname, socklen_t(hostname.count),
                        nil, 0, NI_NUMERICHOST
                    ) == 0 {
                        let ip = String(cString: hostname)
                        if ip.hasPrefix("192.168.") || ip.hasPrefix("10.") || ip.hasPrefix("172.") {
                            result.append(ip)
                        }
                    }
                }
            }
            cursor = interface.ifa_next
        }
        return Array(Set(result)).sorted()
    }
}

struct LanError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

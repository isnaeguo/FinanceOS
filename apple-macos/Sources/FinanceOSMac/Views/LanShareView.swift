import SwiftUI
import Network
import Darwin
import AppKit
import FinanceOSShared

// MARK: - 局域网手动共享

/// 同一局域网内的手动数据同步：本机可开启接收服务，也可作为发起方拉取 / 推送快照。
/// 协议为两端（macOS / Android）一致的最小明文 HTTP/1.1：不使用 chunked，响应一律带 Content-Length。
struct LanShareView: View {
    @Environment(FinanceStore.self) private var store
    @State private var model = LanShareViewModel()

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                receiveCard
                sendCard
                statusCard
            }
            .padding(20)
        }
        .background(AuroraBackground())
        .navigationTitle("局域网共享")
        .onDisappear { model.onPageDisappear() }
    }

    // MARK: - 接收方（服务端）

    private var receiveCard: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("接收对方数据（服务器）", systemImage: "antenna.radiowaves.left.and.right")
                    .font(.headline)
                Text("开启接收后，对方在本页面输入下方“本机地址”，即可把它正在编辑的数据推送过来或拉取本机数据。")
                    .font(.callout)
                    .foregroundStyle(.secondary)

                HStack(spacing: 10) {
                    Text("监听端口")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    TextField("45678", text: serverPortBinding)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 110)
                        .disabled(model.serverRunning)
                    if model.serverRunning {
                        Button(role: .destructive) {
                            model.stopServer()
                        } label: {
                            Label("停止接收", systemImage: "stop.fill")
                        }
                        .buttonStyle(.glass)
                    } else {
                        Button {
                            model.startServer(store: store)
                        } label: {
                            Label("开始接收", systemImage: "play.fill")
                        }
                        .buttonStyle(.glassProminent)
                    }
                }

                if model.serverRunning {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("本机地址（让对方输入）")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Text(model.serverAddress)
                            .font(.system(size: 20, weight: .semibold, design: .monospaced))
                            .foregroundStyle(.teal)
                            .textSelection(.enabled)
                        Label(model.localIP, systemImage: "wifi")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Divider()
                        HStack(spacing: 8) {
                            Text("配对码（仅本次接收会话有效）")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button {
                                model.copyActivePairingCode()
                            } label: {
                                Label("复制", systemImage: "doc.on.doc")
                            }
                            .buttonStyle(.glass)
                            .controlSize(.small)
                        }
                        Text(model.activePairingCode)
                            .font(.system(size: 20, weight: .semibold, design: .monospaced))
                            .foregroundStyle(.orange)
                            .textSelection(.enabled)
                        Text("对方需要输入上方配对码才能拉取或推送数据；停止接收后即失效。")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.teal.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                }
            }
        }
    }

    // MARK: - 发起方（客户端）

    private var sendCard: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("向对方推送 / 拉取数据（客户端）", systemImage: "arrow.triangle.2.circlepath")
                    .font(.headline)
                Text("输入对端已开启接收服务的主机地址与端口，两者须处于同一局域网。操作均为合并导入，不会删除本机数据。")
                    .font(.callout)
                    .foregroundStyle(.secondary)

                HStack(spacing: 10) {
                    Text("主机")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    TextField("例如 192.168.1.8", text: remoteHostBinding)
                        .textFieldStyle(.roundedBorder)
                    Text("端口")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    TextField("45678", text: remotePortBinding)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 110)
                }

                HStack(spacing: 10) {
                    Text("配对码")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    TextField("请输入对方展示的 10 位配对码", text: pairingCodeBinding)
                        .textFieldStyle(.roundedBorder)
                }

                HStack(spacing: 10) {
                    GlassActionButton(
                        title: model.clientBusy ? "正在拉取…" : "拉取对方快照",
                        symbol: "arrow.down.doc.fill",
                        action: { model.pullSnapshot(store: store) }
                    )
                    .disabled(model.clientBusy)

                    GlassActionButton(
                        title: model.clientBusy ? "正在推送…" : "把我的快照推送给对方",
                        symbol: "arrow.up.doc.fill",
                        action: { model.pushSnapshot(store: store) }
                    )
                    .disabled(model.clientBusy)
                }
            }
        }
    }

    // MARK: - 状态与日志

    private var statusCard: some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label("状态与日志", systemImage: "terminal.fill")
                        .font(.headline)
                    Spacer()
                    if !model.logLines.isEmpty {
                        Button("清空") { model.clearLog() }
                            .buttonStyle(.glass)
                            .controlSize(.small)
                    }
                }
                if let message = model.resultMessage {
                    Label(message, systemImage: model.resultIsError ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                        .font(.callout)
                        .foregroundStyle(model.resultIsError ? Color.red : Color.green)
                        .textSelection(.enabled)
                }
                if model.logLines.isEmpty {
                    Text("暂无活动记录。")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                } else {
                    ScrollView {
                        Text(model.logLines.joined(separator: "\n"))
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                    .frame(maxHeight: 120)
                }
            }
        }
    }

    // MARK: - 绑定

    private var serverPortBinding: Binding<String> {
        Binding(get: { model.serverPortText }, set: { model.serverPortText = $0 })
    }

    private var remoteHostBinding: Binding<String> {
        Binding(get: { model.remoteHost }, set: { model.remoteHost = $0 })
    }

    private var remotePortBinding: Binding<String> {
        Binding(get: { model.remotePortText }, set: { model.remotePortText = $0 })
    }

    private var pairingCodeBinding: Binding<String> {
        Binding(get: { model.pairingCodeText }, set: { model.pairingCodeText = $0 })
    }
}

// MARK: - 页面状态

@MainActor
@Observable
final class LanShareViewModel {
    // 服务端（接收方）
    var serverPortText = "45678"
    private(set) var serverRunning = false
    private(set) var localIP = ""
    private(set) var serverAddress = ""
    private(set) var activePairingCode = ""
    private var server: LanShareHTTPServer?

    // 客户端（发起方）
    var remoteHost = ""
    var remotePortText = "45678"
    var pairingCodeText = ""
    private(set) var clientBusy = false

    // 状态与日志
    private(set) var resultMessage: String?
    private(set) var resultIsError = false
    private(set) var logLines: [String] = []
    private let logLimit = 8
    private let deviceId: String = DeviceIdentity.shared.loadOrCreate()

    // MARK: - 服务端控制

    func startServer(store: FinanceStore) {
        guard !serverRunning else { return }
        guard let portNumber = LanShareSupport.validPort(serverPortText) else {
            setResult("端口无效：请输入 1~65535 的整数。", isError: true)
            return
        }
        localIP = LanShareSupport.detectLocalIPv4() ?? ""
        if localIP.isEmpty {
            setResult("未检测到局域网 IP，请确认已连接 Wi-Fi 或以太网后再开启接收。", isError: true)
            return
        }
        do {
            let server = try LanShareHTTPServer(
                port: portNumber,
                exportSnapshot: { [store] in try store.exportJSON() },
                importSnapshot: { [store] snapshot in store.applyMerge(snapshot) },
                logSink: { [weak self] message in self?.appendLog(message) },
                onTerminated: { [weak self] message in self?.handleServerTerminated(message) }
            )
            self.server = server
            let code = LanPairing.shared.generate()
            server.pairingCode = code
            activePairingCode = code
            try server.start()
            serverRunning = true
            serverAddress = "http://\(localIP):\(portNumber)"
            setResult("开始接收：对端请访问 \(serverAddress)", isError: false)
            appendLog("已在端口 \(portNumber) 开启接收服务")
            appendLog("本次配对码：\(code)，仅本次会话有效")
        } catch {
            server = nil
            serverRunning = false
            activePairingCode = ""
            setResult("启动接收服务失败：\(error.localizedDescription)", isError: true)
            appendLog("启动接收失败：\(error.localizedDescription)")
        }
    }

    func stopServer() {
        server?.stop()
        server = nil
        serverRunning = false
        serverAddress = ""
        activePairingCode = ""
        appendLog("已停止接收")
    }

    /// 把当前会话配对码复制到系统剪贴板。
    func copyActivePairingCode() {
        guard !activePairingCode.isEmpty else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(activePairingCode, forType: .string)
        setResult("配对码已复制：\(activePairingCode)", isError: false)
    }

    private func handleServerTerminated(_ message: String) {
        server = nil
        serverRunning = false
        serverAddress = ""
        activePairingCode = ""
        setResult("接收服务已停止：\(message)", isError: true)
        appendLog("接收服务已停止：\(message)")
    }

    /// 页面离开时停止接收，避免服务常驻后台占用端口。
    func onPageDisappear() {
        if serverRunning { stopServer() }
    }

    // MARK: - 客户端操作

    func pullSnapshot(store: FinanceStore) {
        guard !clientBusy else { return }
        guard let url = snapshotURL(), let host = url.host else {
            setResult("地址无效：请输入对端主机地址与端口。", isError: true)
            return
        }
        let code = LanShareSupport.normalizedPairingCode(pairingCodeText)
        guard LanPairing.shared.isValid(code: code) else {
            setResult("配对码无效：请输入对方展示的 10 位配对码。", isError: true)
            return
        }
        let salt = dataOf(LanSyncCrypto.shared.randomBytes(size: 16))
        let device = deviceId
        clientBusy = true
        resultMessage = nil
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let key = try LanSyncCrypto.shared.deriveKey(code: code, salt: kmpBytes(salt))
                var request = URLRequest(url: url, timeoutInterval: 10)
                request.setValue("2", forHTTPHeaderField: "X-FOS-Proto")
                request.setValue(hexString(salt), forHTTPHeaderField: "X-FOS-Salt")
                request.setValue(device, forHTTPHeaderField: "X-FOS-Device-Id")
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse else {
                    throw LanShareError("目标不是 HTTP 服务。")
                }
                guard http.statusCode == 200 else {
                    throw LanShareError(LanShareSupport.serverErrorMessage(from: data, fallback: "对方返回 HTTP \(http.statusCode)。"))
                }
                let snapshotText = try lanDecryptPayloadBody(from: data, key: key)
                // 在后台线程解析备份 JSON，避免大数据拖慢主线程。
                let snapshot = try FinanceDataJsonCodec.decode(snapshotText)
                let result = await MainActor.run { store.applyMerge(snapshot) }
                let summary = "拉取成功：新增流水 \(result.transactionCount) 笔、分类 \(result.categoryCount) 个、预算 \(result.budgetCount) 条。"
                await MainActor.run {
                    self?.setResult(summary, isError: false)
                    self?.appendLog("已从 \(host) 拉取快照并合并到本机")
                    self?.clientBusy = false
                }
            } catch {
                await MainActor.run {
                    self?.handleClientError(error)
                }
            }
        }
    }

    func pushSnapshot(store: FinanceStore) {
        guard !clientBusy else { return }
        guard let url = snapshotURL(), let host = url.host else {
            setResult("地址无效：请输入对端主机地址与端口。", isError: true)
            return
        }
        let code = LanShareSupport.normalizedPairingCode(pairingCodeText)
        guard LanPairing.shared.isValid(code: code) else {
            setResult("配对码无效：请输入对方展示的 10 位配对码。", isError: true)
            return
        }
        let salt = dataOf(LanSyncCrypto.shared.randomBytes(size: 16))
        let iv = dataOf(LanSyncCrypto.shared.randomBytes(size: 16))
        let device = deviceId
        clientBusy = true
        resultMessage = nil
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let json = try await MainActor.run { try store.exportJSON() }
                let key = try LanSyncCrypto.shared.deriveKey(code: code, salt: kmpBytes(salt))
                let payload = LanSyncPayload(
                    proto: 2,
                    alg: lanAlgName,
                    ts: lanNowMillis(),
                    deviceId: device,
                    kind: lanKindSnapshotV2,
                    body: json
                )
                let envelope = dataOf(try LanSyncCrypto.shared.encrypt(
                    key: key,
                    iv: kmpBytes(iv),
                    plaintext: kmpBytes(dataOf(payload.encode())),
                    aad: kmpBytes(Data("2".utf8))
                ))
                var request = URLRequest(url: url, timeoutInterval: 10)
                request.httpMethod = "POST"
                request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
                request.setValue("2", forHTTPHeaderField: "X-FOS-Proto")
                request.setValue(hexString(salt), forHTTPHeaderField: "X-FOS-Salt")
                request.setValue(hexString(iv), forHTTPHeaderField: "X-FOS-Nonce")
                request.setValue(device, forHTTPHeaderField: "X-FOS-Device-Id")
                request.httpBody = envelope
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse else {
                    throw LanShareError("目标不是 HTTP 服务。")
                }
                guard http.statusCode == 200 else {
                    throw LanShareError(LanShareSupport.serverErrorMessage(from: data, fallback: "对方返回 HTTP \(http.statusCode)。"))
                }
                let resultText = try lanDecryptPayloadBody(from: data, key: key)
                let summary = LanShareSupport.importedSummary(from: resultText) ?? "推送成功：对方已接收快照。"
                await MainActor.run {
                    self?.setResult(summary, isError: false)
                    self?.appendLog("已把本机快照推送给 \(host)")
                    self?.clientBusy = false
                }
            } catch {
                await MainActor.run {
                    self?.handleClientError(error)
                }
            }
        }
    }

    private func handleClientError(_ error: Error) {
        clientBusy = false
        let text = LanShareSupport.readableClientError(error)
        setResult(text, isError: true)
        appendLog(text)
    }

    // MARK: - 地址与状态

    private func snapshotURL() -> URL? {
        guard let host = LanShareSupport.normalizedHost(remoteHost) else { return nil }
        guard let portNumber = LanShareSupport.validPort(remotePortText) else { return nil }
        var components = URLComponents()
        components.scheme = "http"
        components.host = host
        components.port = Int(portNumber)
        components.path = "/api/snapshot"
        return components.url
    }

    private func setResult(_ text: String, isError: Bool) {
        resultMessage = text
        resultIsError = isError
    }

    private func appendLog(_ message: String) {
        let line = "\(LanShareSupport.timestamp())  \(message)"
        logLines.append(line)
        if logLines.count > logLimit {
            logLines.removeFirst(logLines.count - logLimit)
        }
    }

    func clearLog() {
        logLines.removeAll()
        resultMessage = nil
    }
}

// MARK: - 纯文本 / 网络辅助（非 UI 状态）

private struct LanShareError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

private enum LanShareSupport {
    /// 规整用户输入的主机：去掉协议头与多余路径，返回可用的主机名。
    static func normalizedHost(_ raw: String) -> String? {
        var host = raw.trimmingCharacters(in: .whitespaces)
        if host.isEmpty { return nil }
        if let schemeRange = host.range(of: "://") {
            host = String(host[schemeRange.upperBound...])
        }
        if let slash = host.firstIndex(of: "/") {
            host = String(host[..<slash])
        }
        if let colon = host.lastIndex(of: ":") {
            // 允许直接在主机框输入 host:port 形式。
            let after = host[host.index(after: colon)...]
            if !after.isEmpty && after.allSatisfy(\.isNumber) {
                host = String(host[..<colon])
            }
        }
        host = host.trimmingCharacters(in: .whitespaces)
        return host.isEmpty ? nil : host
    }

    static func validPort(_ text: String) -> UInt16? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard let number = Int(trimmed), number >= 1, number <= 65535 else { return nil }
        return UInt16(number)
    }

    /// 规整用户输入的配对码：去掉首尾空白并统一大写（Base32 字母表为大写）。
    static func normalizedPairingCode(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    /// 遍历本机网络接口，返回 en0/en1 上第一个可用的局域网 IPv4 地址。
    static func detectLocalIPv4() -> String? {
        var address: String?
        var pointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&pointer) == 0, let first = pointer else { return nil }
        defer { freeifaddrs(pointer) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let current = cursor {
            defer { cursor = current.pointee.ifa_next }
            guard let ifaAddress = current.pointee.ifa_addr else { continue }
            let family = ifaAddress.pointee.sa_family
            guard family == UInt8(AF_INET) else { continue }
            let flags = Int32(current.pointee.ifa_flags)
            guard (flags & IFF_UP) == IFF_UP, (flags & IFF_LOOPBACK) == 0 else { continue }
            let name = String(validatingCString: current.pointee.ifa_name) ?? ""
            guard name == "en0" || name == "en1" else { continue }
            var hostBuffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(ifaAddress, socklen_t(ifaAddress.pointee.sa_len), &hostBuffer, socklen_t(hostBuffer.count), nil, 0, NI_NUMERICHOST) == 0 {
                let valid = hostBuffer.prefix { $0 != 0 }
                let bytes = valid.map { UInt8(bitPattern: $0) }
                address = String(decoding: bytes, as: UTF8.self)
                break
            }
        }
        return address
    }

    /// 从服务器返回的错误体里尽量提取 {"error": "..."} 文本。
    static func serverErrorMessage(from data: Data, fallback: String) -> String {
        if let object = try? JSONSerialization.jsonObject(with: data),
           let dict = object as? [String: Any],
           let message = dict["error"] as? String {
            return message
        }
        return fallback
    }

    /// 解析推送成功后的 {"imported": {...}} 汇总。
    static func importedSummary(from data: Data) -> String? {
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let dict = object as? [String: Any],
              let imported = dict["imported"] as? [String: Any],
              let transactions = imported["transactions"] as? Int,
              let categories = imported["categories"] as? Int,
              let budgets = imported["budgets"] as? Int else { return nil }
        return "推送成功：对方合并流水 \(transactions) 笔、分类 \(categories) 个、预算 \(budgets) 条。"
    }

    /// 解析推送成功后的 {"imported": {...}} 汇总（来自解密后 payload 文本）。
    static func importedSummary(from text: String) -> String? {
        importedSummary(from: Data(text.utf8))
    }

    /// 把任意网络错误翻译成用户可读的中文提示。
    static func readableClientError(_ error: Error) -> String {
        if let lanError = error as? LanShareError {
            return lanError.message
        }
        if let transferError = error as? DataTransferError {
            return transferError.message
        }
        if let urlError = error as? URLError {
            switch urlError.code {
            case .timedOut:
                return "连接超时：请确认对端已开启“开始接收”且地址端口正确。"
            case .cannotConnectToHost, .cannotFindHost:
                return "无法连接到目标主机：请检查 IP、端口以及两台设备是否在同一局域网。"
            case .networkConnectionLost, .notConnectedToInternet:
                return "网络连接已断开。"
            case .cancelled:
                return "操作已取消。"
            default:
                break
            }
        }
        return "网络错误：\(error.localizedDescription)"
    }

    static func timestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: Date())
    }
}

// MARK: - 配对加密辅助（shared FinanceOSShared）

private let lanAlgName = "AES-256-CBC+HMAC-SHA256"
private let lanKindSnapshotV2 = "snapshot_v2"
private let lanKindSnapshotResponse = "snapshot_response"
private let lanKindImportResult = "import_result"

private func lanNowMillis() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000).rounded())
}

private func kmpBytes(_ data: Data) -> KotlinByteArray {
    KotlinByteArray(size: Int32(data.count)) { index in
        KotlinByte(char: Int8(bitPattern: data[Int(index.intValue)]))
    }
}

private func dataOf(_ bytes: KotlinByteArray) -> Data {
    var out = Data(count: Int(bytes.size))
    for index in 0..<Int(bytes.size) {
        out[index] = UInt8(bitPattern: bytes.get(index: Int32(index)))
    }
    return out
}

private func hexString(_ data: Data) -> String {
    let digits = Array("0123456789abcdef")
    var result = ""
    result.reserveCapacity(data.count * 2)
    for byte in data {
        let value = Int(byte)
        result.append(digits[value >> 4])
        result.append(digits[value & 0x0F])
    }
    return result
}

private func hexData(_ text: String) -> Data? {
    let characters = Array(text)
    guard characters.count % 2 == 0 else { return nil }
    var bytes: [UInt8] = []
    bytes.reserveCapacity(characters.count / 2)
    var index = 0
    while index < characters.count {
        guard let high = characters[index].hexDigitValue,
              let low = characters[index + 1].hexDigitValue else { return nil }
        bytes.append(UInt8((high << 4) | low))
        index += 2
    }
    return Data(bytes)
}

/// 解密响应信封并取出明文 payload 的 body（snapshot 或 import_result 的 JSON 文本）。
private func lanDecryptPayloadBody(from envelope: Data, key: KotlinByteArray) throws -> String {
    let plaintext: Data
    do {
        plaintext = dataOf(try LanSyncCrypto.shared.decrypt(key: key, envelope: kmpBytes(envelope), aad: kmpBytes(Data("2".utf8))))
    } catch {
        throw LanShareError("配对码错误或数据已损坏。")
    }
    let payload = LanSyncPayload.companion.decode(bytes: kmpBytes(plaintext))
    return payload.body
}

/// 小对象锁保护（配对码由主线程写入、服务队列读取）。
private final class ProtectedValue<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Value

    init(_ value: Value) {
        storage = value
    }

    var value: Value {
        get {
            lock.lock()
            defer { lock.unlock() }
            return storage
        }
        set {
            lock.lock()
            defer { lock.unlock() }
            storage = newValue
        }
    }
}

// MARK: - 明文 HTTP/1.1 接收服务

/// 基于 Network.framework（NWListener/TCP）的最小明文 HTTP/1.1 服务。
/// 每个连接只服务一个请求：读完整请求 → 响应 → 关闭。不使用 chunked，响应一律带 Content-Length。
final class LanShareHTTPServer: @unchecked Sendable {
    typealias ExportSnapshot = @MainActor () throws -> String
    typealias ImportSnapshot = @MainActor (FinanceDataSnapshot) throws -> FinanceDataImportResult
    typealias LogSink = @MainActor (String) -> Void
    typealias TerminatedHandler = @MainActor (String) -> Void

    private let requestedPort: NWEndpoint.Port
    private let exportSnapshot: ExportSnapshot
    private let importSnapshot: ImportSnapshot
    private let logSink: LogSink
    private let onTerminated: TerminatedHandler

    private let workQueue = DispatchQueue(label: "com.financeos.mac.lanshare.server")
    private var listener: NWListener?

    // v2 配对加密会话状态：
    /// 当前会话配对码（页面开启接收前注入，停止接收后置空）。主线程写、服务队列读，故加锁。
    private let pairingCodeBox = ProtectedValue<String?>(nil)
    var pairingCode: String? {
        get { pairingCodeBox.value }
        set { pairingCodeBox.value = newValue }
    }

    /// 最近见过的 nonce（防重放，上限 1024）。仅在 workQueue 访问。
    private var recentNonces = Set<Data>()
    private var recentNonceOrder: [Data] = []

    /// 连续认证失败计数与会话锁定标志。仅在 workQueue 访问。
    private var authFailureCount = 0
    private var authLocked = false

    private static let headerTerminator = Data("\r\n\r\n".utf8)
    private static let maxHeaderBytes = 16 * 1024
    private static let maxBodyBytes = 64 * 1024 * 1024
    private static let maxRequestBytes = maxBodyBytes + maxHeaderBytes

    init(
        port: UInt16,
        exportSnapshot: @escaping ExportSnapshot,
        importSnapshot: @escaping ImportSnapshot,
        logSink: @escaping LogSink,
        onTerminated: @escaping TerminatedHandler
    ) throws {
        guard let port = NWEndpoint.Port(rawValue: port) else {
            throw LanShareError("端口无效。")
        }
        requestedPort = port
        self.exportSnapshot = exportSnapshot
        self.importSnapshot = importSnapshot
        self.logSink = logSink
        self.onTerminated = onTerminated
    }

    func start() throws {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: requestedPort)
        self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        listener.stateUpdateHandler = { [weak self] state in
            self?.handleListenerState(state)
        }
        listener.start(queue: workQueue)
        report("监听端口 \(requestedPort.rawValue)，等待连接…")
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    // MARK: - 监听状态

    private func handleListenerState(_ state: NWListener.State) {
        switch state {
        case .ready:
            report("接收服务已就绪")
        case .failed(let error):
            listener = nil
            let message = Self.describeNetworkError(error)
            report("接收服务失败：\(message)")
            let terminated = onTerminated
            Task { @MainActor in
                terminated(message)
            }
        case .cancelled:
            listener = nil
        default:
            break
        }
    }

    // MARK: - 连接处理

    private func accept(_ connection: NWConnection) {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else {
                connection.cancel()
                return
            }
            switch state {
            case .ready:
                report("已连接：\(Self.endpointDescription(connection.endpoint))")
                serve(connection)
            case .failed(let error):
                report("连接失败：\(Self.describeNetworkError(error))")
                connection.cancel()
            case .cancelled:
                connection.stateUpdateHandler = nil
            default:
                break
            }
        }
        connection.start(queue: workQueue)
    }

    private func serve(_ connection: NWConnection) {
        // 兜底超时：对端迟迟不发送完整请求时主动关闭，避免连接悬挂。
        workQueue.asyncAfter(deadline: .now() + 25) { [weak connection] in
            connection?.cancel()
        }
        readMore(connection: connection, buffer: Data())
    }

    private func readMore(connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else {
                connection.cancel()
                return
            }
            if error != nil {
                connection.cancel()
                return
            }
            var accumulated = buffer
            if let data { accumulated.append(data) }
            guard accumulated.count <= Self.maxRequestBytes else {
                report("请求过大，已关闭连接。")
                connection.cancel()
                return
            }
            if let parsed = Self.tryParseRequest(from: accumulated) {
                handle(parsed: parsed, connection: connection)
                return
            }
            if isComplete {
                // 数据读完了但请求不完整。
                send(errorResponse: "请求不完整，已关闭连接。", to: connection)
                return
            }
            readMore(connection: connection, buffer: accumulated)
        }
    }

    // MARK: - 请求解析

    private enum ParsedRequest {
        case malformed(String)
        case complete(method: String, path: String, fields: [String: String], body: Data)
    }

    /// 已解析的请求行与头部信息（字段名一律小写）。
    private struct ParsedHeader {
        let method: String
        let path: String
        let fields: [String: String]

        var contentLength: Int {
            fields["content-length"].flatMap { Int($0) } ?? 0
        }
    }

    private static func tryParseRequest(from buffer: Data) -> ParsedRequest? {
        guard let terminatorRange = buffer.range(of: headerTerminator) else {
            if buffer.count > maxHeaderBytes {
                return .malformed("请求头过大。")
            }
            return nil
        }
        if terminatorRange.lowerBound > maxHeaderBytes {
            return .malformed("请求头过大。")
        }
        let headerText = String(decoding: buffer[..<terminatorRange.lowerBound], as: UTF8.self)
        let outcome = parseHeader(headerText)
        if let error = outcome.error {
            return .malformed(error)
        }
        guard let parsed = outcome.header else {
            return .malformed("请求头无效。")
        }
        let bodyStart = terminatorRange.upperBound
        let totalLength = bodyStart + parsed.contentLength
        guard buffer.count >= totalLength else { return nil }
        let body = buffer.subdata(in: bodyStart..<totalLength)
        return .complete(method: parsed.method, path: parsed.path, fields: parsed.fields, body: body)
    }

    private static func parseHeader(_ text: String) -> (header: ParsedHeader?, error: String?) {
        let lines = text.components(separatedBy: "\r\n")
        guard let requestLine = lines.first, !requestLine.isEmpty else {
            return (nil, "请求行为空。")
        }
        let parts = requestLine.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: true).map(String.init)
        guard parts.count >= 2 else { return (nil, "请求行格式无效。") }
        let method = parts[0]
        let path = parts[1]
        guard method == "GET" || method == "POST" else { return (nil, "不支持的请求方法：\(method)。") }
        guard path.hasPrefix("/") else { return (nil, "请求路径无效。") }
        if parts.count >= 3, !parts[2].hasPrefix("HTTP/") {
            return (nil, "仅支持 HTTP/1.x 明文请求。")
        }

        var fields: [String: String] = [:]
        for line in lines.dropFirst() {
            let pair = line.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            guard pair.count == 2 else { continue }
            let name = pair[0].trimmingCharacters(in: .whitespaces).lowercased()
            let value = pair[1].trimmingCharacters(in: .whitespaces)
            switch name {
            case "content-length":
                guard let length = Int(value), length >= 0, length <= maxBodyBytes else {
                    return (nil, "Content-Length 无效或请求体过大。")
                }
            case "transfer-encoding":
                return (nil, "不支持 chunked 传输编码。")
            default:
                break
            }
            fields[name] = value
        }
        return (ParsedHeader(method: method, path: path, fields: fields), nil)
    }

    // MARK: - 路由与响应

    private func handle(parsed: ParsedRequest, connection: NWConnection) {
        switch parsed {
        case .malformed(let message):
            report("请求格式错误：\(message)")
            send(errorResponse: message, to: connection)
        case .complete(let method, let rawPath, let fields, let body):
            let path = rawPath.split(separator: "?", maxSplits: 1).first.map(String.init) ?? rawPath
            let response = makeResponse(method: method, path: path, fields: fields, body: body)
            report("\(method) \(path) → \(response.statusCode)")
            send(response, to: connection)
        }
    }

    private func makeResponse(method: String, path: String, fields: [String: String], body: Data) -> LanHTTPResponse {
        switch (method, path) {
        case ("GET", "/api/ping"):
            // /api/ping 不承载业务数据，保持明文以便地址探测。
            return LanHTTPResponse.json(
                200,
                ["status": "ok", "device": "FinanceOSMac"],
                contentType: "application/json; charset=utf-8"
            )
        case ("GET", "/api/snapshot"):
            return makeEncryptedResponse(method: method, fields: fields, requestBody: body)
        case ("POST", "/api/snapshot"):
            return makeEncryptedResponse(method: method, fields: fields, requestBody: body)
        default:
            return LanHTTPResponse.error(404, "未找到接口：\(method) \(path)")
        }
    }

    // MARK: - v2 配对加密端点（GET/POST /api/snapshot）

    /// 受保护快照端点：加解密全部在本请求工作队列完成，仅在导出/导入快照时经 hopToMain 回主线程。
    private func makeEncryptedResponse(method: String, fields: [String: String], requestBody: Data) -> LanHTTPResponse {
        guard let code = pairingCode, !code.isEmpty else {
            return LanHTTPResponse.error(503, "接收会话未开始，请在本机重新开启接收。")
        }
        if authLocked {
            return policyResponse(429, LanSyncPolicy.shared.rateLimitedBody())
        }
        guard fields["x-fos-proto"] == "2" else {
            return policyResponse(426, LanSyncPolicy.shared.upgradeRequiredBody())
        }

        // salt：POST 必带；GET 缺省时服务端生成并在响应头回写。
        let saltHex = fields["x-fos-salt"]
        let salt: Data
        let saltEchoHex: String?
        if let saltHex {
            guard let decoded = hexData(saltHex) else {
                return LanHTTPResponse.error(400, "请求头 X-FOS-Salt 不是合法十六进制。")
            }
            salt = decoded
            saltEchoHex = nil
        } else if method == "GET" {
            salt = dataOf(LanSyncCrypto.shared.randomBytes(size: 16))
            saltEchoHex = hexString(salt)
        } else {
            return LanHTTPResponse.error(400, "缺少请求头 X-FOS-Salt。")
        }

        let key: KotlinByteArray
        do {
            key = try LanSyncCrypto.shared.deriveKey(code: code, salt: kmpBytes(salt))
        } catch {
            return authFailureResponse()
        }

        if method == "POST" {
            return makeEncryptedPOSTResponse(key: key, fields: fields, requestBody: requestBody)
        }
        return makeEncryptedGETResponse(key: key, saltEchoHex: saltEchoHex)
    }

    /// GET：导出本机快照 → snapshot_response payload → 加密信封。
    private func makeEncryptedGETResponse(key: KotlinByteArray, saltEchoHex: String?) -> LanHTTPResponse {
        let outcome = Self.hopToMain { try self.exportSnapshot() }
        switch outcome {
        case .success(let json):
            return encryptedPayloadResponse(bodyText: json, kind: lanKindSnapshotResponse, key: key, saltEchoHex: saltEchoHex)
        case .failure(let error):
            return LanHTTPResponse.error(400, Self.friendlyMessage(for: error))
        }
    }

    /// POST：解密 → 校验新鲜度/防重放 → 导入快照 → import_result payload → 加密信封。
    private func makeEncryptedPOSTResponse(key: KotlinByteArray, fields: [String: String], requestBody: Data) -> LanHTTPResponse {
        guard let nonceHex = fields["x-fos-nonce"] else {
            return LanHTTPResponse.error(400, "缺少请求头 X-FOS-Nonce。")
        }
        guard let nonce = hexData(nonceHex) else {
            return LanHTTPResponse.error(400, "请求头 X-FOS-Nonce 不是合法十六进制。")
        }
        if recentNonces.contains(nonce) {
            return LanHTTPResponse.error(400, "请求重复（nonce 已使用）。")
        }

        let plaintext: Data
        do {
            plaintext = dataOf(try LanSyncCrypto.shared.decrypt(key: key, envelope: kmpBytes(requestBody), aad: kmpBytes(Data("2".utf8))))
        } catch {
            return authFailureResponse()
        }
        let payload = LanSyncPayload.companion.decode(bytes: kmpBytes(plaintext))
        guard LanSyncPolicy.shared.isTimestampFresh(ts: payload.ts, nowMillis: lanNowMillis()) else {
            return policyResponse(400, LanSyncPolicy.shared.staleTimestampBody())
        }

        // 记录 nonce 防重放（上限 1024，LRU 淘汰）。
        recentNonces.insert(nonce)
        recentNonceOrder.append(nonce)
        if recentNonceOrder.count > 1024 {
            recentNonceOrder.removeFirst(recentNonceOrder.count - 1024)
            recentNonces = Set(recentNonceOrder)
        }

        // body 为快照 JSON：先在后台线程解码校验，再回主线程合并写入。
        let snapshot: FinanceDataSnapshot
        do {
            snapshot = try FinanceDataJsonCodec.decode(payload.body)
        } catch {
            return LanHTTPResponse.error(400, Self.friendlyMessage(for: error))
        }
        let outcome = Self.hopToMain { try self.importSnapshot(snapshot) }
        switch outcome {
        case .success(let imported):
            resetAuthFailures()
            let importedJSON = "{\"imported\":{\"transactions\":\(imported.transactionCount),\"categories\":\(imported.categoryCount),\"budgets\":\(imported.budgetCount)}}"
            return encryptedPayloadResponse(bodyText: importedJSON, kind: lanKindImportResult, key: key, saltEchoHex: nil)
        case .failure(let error):
            return LanHTTPResponse.error(400, Self.friendlyMessage(for: error))
        }
    }

    /// 构造业务明文 payload → 加密为信封响应（响应头 X-FOS-Nonce = 本次 IV；GET 生成 salt 时回写 X-FOS-Salt）。
    private func encryptedPayloadResponse(bodyText: String, kind: String, key: KotlinByteArray, saltEchoHex: String?) -> LanHTTPResponse {
        do {
            let iv = dataOf(LanSyncCrypto.shared.randomBytes(size: 16))
            let payload = LanSyncPayload(
                proto: 2,
                alg: lanAlgName,
                ts: lanNowMillis(),
                deviceId: DeviceIdentity.shared.loadOrCreate(),
                kind: kind,
                body: bodyText
            )
            let envelope = dataOf(try LanSyncCrypto.shared.encrypt(
                key: key,
                iv: kmpBytes(iv),
                plaintext: kmpBytes(dataOf(payload.encode())),
                aad: kmpBytes(Data("2".utf8))
            ))
            var extraHeaders = ["X-FOS-Nonce": hexString(iv)]
            if let saltEchoHex {
                extraHeaders["X-FOS-Salt"] = saltEchoHex
            }
            return LanHTTPResponse(statusCode: 200, contentType: "application/octet-stream", body: envelope, extraHeaders: extraHeaders)
        } catch {
            return LanHTTPResponse.error(500, "加密响应失败，请稍后重试。")
        }
    }

    /// 认证失败：累计计数，连续 5 次后本会话内锁定为 429。
    private func authFailureResponse() -> LanHTTPResponse {
        authFailureCount += 1
        if authFailureCount >= 5 {
            authLocked = true
            return policyResponse(429, LanSyncPolicy.shared.rateLimitedBody())
        }
        return policyResponse(401, LanSyncPolicy.shared.authFailedBody())
    }

    private func resetAuthFailures() {
        authFailureCount = 0
    }

    /// LanSyncPolicy 的错误体本身就是 {"error": ...} JSON 文本，直接作为响应体返回。
    private func policyResponse(_ status: Int, _ jsonBody: String) -> LanHTTPResponse {
        LanHTTPResponse(statusCode: status, contentType: "application/json; charset=utf-8", body: Data(jsonBody.utf8))
    }

    private static func friendlyMessage(for error: Error) -> String {
        if let dataError = error as? DataTransferError {
            return dataError.message
        }
        return error.localizedDescription
    }

    // MARK: - 发送 / 日志

    private func send(_ response: LanHTTPResponse, to connection: NWConnection) {
        connection.send(content: response.serialized(), completion: .contentProcessed { [weak self] error in
            if let error, let self {
                self.report("发送响应失败：\(error.localizedDescription)")
            }
            connection.cancel()
        })
    }

    private func send(errorResponse message: String, to connection: NWConnection) {
        send(LanHTTPResponse.error(400, message), to: connection)
    }

    /// 把需要在主线程执行的快照导出 / 导入任务同步拿到结果；本方法运行在网络工作队列。
    private static func hopToMain<Value>(_ operation: @escaping @MainActor () throws -> Value) -> Result<Value, Error> {
        let box = OperationBox<Value>()
        Task { @MainActor in
            box.result = Result { try operation() }
            box.semaphore.signal()
        }
        box.semaphore.wait()
        return box.result!
    }

    private final class OperationBox<Value>: @unchecked Sendable {
        let semaphore = DispatchSemaphore(value: 0)
        var result: Result<Value, Error>?
    }

    private func report(_ message: String) {
        let sink = logSink
        Task { @MainActor in
            sink(message)
        }
    }

    // MARK: - 描述辅助

    private static func endpointDescription(_ endpoint: NWEndpoint) -> String {
        switch endpoint {
        case .hostPort(let host, let port):
            return "\(host):\(port.rawValue)"
        default:
            return endpoint.debugDescription
        }
    }

    private static func describeNetworkError(_ error: NWError) -> String {
        switch error {
        case .posix(let code):
            switch code {
            case .EADDRINUSE:
                return "端口已被占用，请更换端口后重试。"
            case .EACCES:
                return "没有权限使用该端口。"
            default:
                return "网络错误（POSIX 码 \(code.rawValue)）。"
            }
        default:
            return error.localizedDescription
        }
    }
}

// MARK: - 最小 HTTP 响应

private struct LanHTTPResponse {
    var statusCode: Int
    var statusText: String
    var contentType: String
    var body: Data
    var extraHeaders: [String: String]

    init(statusCode: Int, contentType: String, body: Data, extraHeaders: [String: String] = [:]) {
        self.statusCode = statusCode
        self.contentType = contentType
        self.body = body
        self.extraHeaders = extraHeaders
        statusText = Self.reason(statusCode)
    }

    /// 序列化为 HTTP/1.1 明文响应，不使用 chunked。
    func serialized() -> Data {
        var head = "HTTP/1.1 \(statusCode) \(statusText)\r\n"
        for (name, value) in extraHeaders.sorted(by: { $0.key < $1.key }) {
            head += "\(name): \(value)\r\n"
        }
        head += "Content-Type: \(contentType)\r\n"
            + "Content-Length: \(body.count)\r\n"
            + "Connection: close\r\n\r\n"
        var data = Data(head.utf8)
        data.append(body)
        return data
    }

    static func json(_ statusCode: Int, _ object: [String: Any], contentType: String = "application/json") -> LanHTTPResponse {
        let payload = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
        return LanHTTPResponse(statusCode: statusCode, contentType: contentType, body: payload)
    }

    static func error(_ statusCode: Int, _ message: String) -> LanHTTPResponse {
        let object: [String: Any] = ["error": message]
        return json(statusCode, object)
    }

    private static func reason(_ statusCode: Int) -> String {
        switch statusCode {
        case 200: "OK"
        case 400: "Bad Request"
        case 401: "Unauthorized"
        case 404: "Not Found"
        case 413: "Payload Too Large"
        case 426: "Upgrade Required"
        case 429: "Too Many Requests"
        case 500: "Internal Server Error"
        case 503: "Service Unavailable"
        default: "Error"
        }
    }
}

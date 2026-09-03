import SwiftUI
import Network
import Observation
import Foundation
import FinanceOSShared
#if os(iOS)
import UIKit
#endif

/// 局域网手动共享（iOS）：与 macOS / Android 同一 HTTP 协议。
/// GET /api/ping 明文探活；GET/POST /api/snapshot 受配对码 + AES-CBC/HMAC 加密保护（docs/lan-sync-protocol.md）。
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
                    HStack {
                        Text("配对码")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Text(model.pairingCode.isEmpty ? "生成中…" : model.pairingCode)
                            .font(.callout.monospaced())
                            .textSelection(.enabled)
                        Spacer()
#if os(iOS)
                        Button("复制") {
                            UIPasteboard.general.string = model.pairingCode
                        }
                        .font(.caption)
                        .disabled(model.pairingCode.isEmpty)
#else
                        Text("")
#endif
                    }
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
                TextField("配对码（对端接收页面的 10 位字符）", text: $model.pairingInput)
#if os(iOS)
                    .textInputAutocapitalization(.characters)
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

// MARK: - 协议常量与纯函数辅助

private let fosProtoHeader = "2"
private let fosAlg = "AES-256-CBC+HMAC-SHA256"
private let fosKindSnapshotV2 = "snapshot_v2"
private let fosKindSnapshotResponse = "snapshot_response"
private let fosKindImportResult = "import_result"
private let fosSaltBytes: Int32 = 16
private let fosIVBytes: Int32 = 16
private let fosMaxAuthFailures = 5
private let fosNonceMemory = 1024
private let fosMaxBodyBytes = 64 * 1024 * 1024
private let fosMaxRequestBytes = fosMaxBodyBytes + 64 * 1024
private let fosReadChunk = 64 * 1024
private let fosRequestTimeoutNanos: UInt64 = 25 * 1_000_000_000

private struct FosEnvelopeResult {
    let envelope: Data
    let saltHex: String
    let nonceHex: String
}

private struct FosPlainPayload {
    let ts: Int64
    let deviceId: String
    let kind: String
    let body: String
}

/// 已解析的完整请求（head + Content-Length 对齐后的 body）。
private struct LanParsedRequest {
    let method: String
    let path: String
    let headers: [String: String]
    let body: Data
}

private func fosKotlinBytes(_ data: Data) -> KotlinByteArray {
    KotlinByteArray(size: Int32(data.count)) { i in
        KotlinByte(char: Int8(bitPattern: data[Int(i.intValue)]))
    }
}

private func fosData(_ bytes: KotlinByteArray) -> Data {
    let count = Int(bytes.size)
    var data = Data(capacity: count)
    for index in 0..<count {
        data.append(UInt8(bitPattern: bytes.get(index: Int32(index))))
    }
    return data
}

private func fosHexString(_ bytes: KotlinByteArray) -> String {
    fosHexString(fosData(bytes))
}

private func fosHexString(_ data: Data) -> String {
    data.map { String(format: "%02x", $0) }.joined()
}

private func fosData(hex: String) -> Data? {
    var clean = hex.trimmingCharacters(in: .whitespaces)
    if clean.hasPrefix("0x") { clean.removeFirst(2) }
    guard clean.count % 2 == 0 else { return nil }
    var data = Data(capacity: clean.count / 2)
    var index = clean.startIndex
    while index < clean.endIndex {
        let next = clean.index(index, offsetBy: 2)
        guard let byte = UInt8(clean[index..<next], radix: 16) else { return nil }
        data.append(byte)
        index = next
    }
    return data
}

private func fosAad() -> KotlinByteArray {
    fosKotlinBytes(Data(fosProtoHeader.utf8))
}

private func fosNowMillis() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}

private func fosDeviceIdentity() -> String {
    DeviceIdentity.shared.loadOrCreate()
}

private func fosJsonErrorBody(_ message: String) -> Data {
    let object = ["error": message]
    let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{\"error\":\"error\"}".utf8)
    return data
}

/// 在后台线程执行同步计算，避免 PBKDF2 / AES / JSON 解码拖住主线程。
private func fosDetached<Value>(_ operation: @escaping () throws -> Value) async throws -> Value {
    try await Task.detached(priority: .userInitiated, operation: operation).value
}

/// 服务端 GET 响应：随机 salt/iv，密钥派生后加密快照信封。
private func fosBuildSnapshotEnvelope(code: String, snapshotJSON: String) throws -> FosEnvelopeResult {
    let salt = LanSyncCrypto.shared.randomBytes(size: fosSaltBytes)
    let key = try LanSyncCrypto.shared.deriveKey(code: code, salt: salt)
    let iv = LanSyncCrypto.shared.randomBytes(size: fosIVBytes)
    let payload = LanSyncPayload(
        proto: Int32(2), alg: fosAlg, ts: fosNowMillis(),
        deviceId: fosDeviceIdentity(), kind: fosKindSnapshotResponse, body: snapshotJSON
    )
    let envelope = try LanSyncCrypto.shared.encrypt(key: key, iv: iv, plaintext: payload.encode(), aad: fosAad())
    return FosEnvelopeResult(envelope: fosData(envelope), saltHex: fosHexString(salt), nonceHex: fosHexString(iv))
}

/// 服务端 POST 响应：复用请求的 salt，用新 iv 加密导入结果信封。
private func fosBuildImportEnvelope(code: String, saltHex: String, resultJSON: String) throws -> FosEnvelopeResult {
    guard let saltData = fosData(hex: saltHex) else { throw LanError("盐值无效") }
    let key = try LanSyncCrypto.shared.deriveKey(code: code, salt: fosKotlinBytes(saltData))
    let iv = LanSyncCrypto.shared.randomBytes(size: fosIVBytes)
    let payload = LanSyncPayload(
        proto: Int32(2), alg: fosAlg, ts: fosNowMillis(),
        deviceId: fosDeviceIdentity(), kind: fosKindImportResult, body: resultJSON
    )
    let envelope = try LanSyncCrypto.shared.encrypt(key: key, iv: iv, plaintext: payload.encode(), aad: fosAad())
    return FosEnvelopeResult(envelope: fosData(envelope), saltHex: saltHex, nonceHex: fosHexString(iv))
}

/// 解密并解析收到的信封 → payload。
private func fosDecryptEnvelope(code: String, envelope: Data, saltHex: String) throws -> FosPlainPayload {
    guard let saltData = fosData(hex: saltHex) else { throw LanError("响应缺少有效 X-FOS-Salt") }
    let key = try LanSyncCrypto.shared.deriveKey(code: code, salt: fosKotlinBytes(saltData))
    let plaintext = try LanSyncCrypto.shared.decrypt(key: key, envelope: fosKotlinBytes(envelope), aad: fosAad())
    let payload = LanSyncPayload.companion.decode(bytes: plaintext)
    return FosPlainPayload(ts: payload.ts, deviceId: payload.deviceId, kind: payload.kind, body: payload.body)
}

/// 服务端错误体 {"error": ...} 提取中文文案。
private func fosServerErrorText(from data: Data) -> String? {
    guard let object = try? JSONSerialization.jsonObject(with: data),
          let dict = object as? [String: Any],
          let message = dict["error"] as? String else { return nil }
    return message
}

private func fosImportedSummary(_ body: String) -> String? {
    guard let data = body.data(using: .utf8),
          let object = try? JSONSerialization.jsonObject(with: data),
          let dict = object as? [String: Any],
          let imported = dict["imported"] as? [String: Any],
          let transactions = imported["transactions"] as? Int,
          let categories = imported["categories"] as? Int,
          let budgets = imported["budgets"] as? Int else { return nil }
    return "推送完成，对方已合并导入：新增 \(transactions) 笔流水、\(categories) 个分类、\(budgets) 条预算"
}

private func fosReadableClientError(_ error: Error) -> String {
    if let lanError = error as? LanError {
        return lanError.message
    }
    if let urlError = error as? URLError {
        switch urlError.code {
        case .timedOut:
            return "连接超时：请确认对端已开启接收且地址端口正确"
        case .cannotConnectToHost, .cannotFindHost:
            return "无法连接到目标主机：请检查 IP、端口以及两台设备是否在同一局域网"
        case .networkConnectionLost, .notConnectedToInternet:
            return "网络连接已断开"
        case .cancelled:
            return "操作已取消"
        default:
            break
        }
    }
    return error.localizedDescription
}

/// 从累积缓冲中解析出一个完整请求；数据不足时返回 nil 等待继续读取。
private enum LanRequestParser {
    static let headerTerminator = Data("\r\n\r\n".utf8)

    static func tryParse(_ buffer: Data) -> LanParsedRequest? {
        guard let terminator = buffer.range(of: headerTerminator) else { return nil }
        let headerText = String(decoding: buffer[..<terminator.lowerBound], as: UTF8.self)
        let lines = headerText.components(separatedBy: "\r\n")
        guard let requestLine = lines.first, !requestLine.isEmpty else { return nil }
        let parts = requestLine.split(separator: " ", maxSplits: 2, omittingEmptySubsequences: true).map(String.init)
        guard parts.count >= 2 else { return nil }
        let method = parts[0]
        let path = parts[1]
        guard method == "GET" || method == "POST" else { return nil }
        guard path.hasPrefix("/") else { return nil }

        var headers: [String: String] = [:]
        var contentLength = 0
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            if name == "content-length" {
                guard let length = Int(value), length >= 0, length <= fosMaxBodyBytes else { return nil }
                contentLength = length
            } else {
                headers[name] = value
            }
        }
        let bodyStart = terminator.upperBound
        let totalLength = bodyStart + contentLength
        guard buffer.count >= totalLength else { return nil }
        let body = buffer.subdata(in: bodyStart..<totalLength)
        return LanParsedRequest(method: method, path: path, headers: headers, body: body)
    }
}

@MainActor
@Observable
final class LanShareModel {
    var portText = "45678"
    var host = ""
    /// 接收方当前会话的一次性配对码（展示给对端）。
    var pairingCode = ""
    /// 发送方填写的对端配对码。
    var pairingInput = ""
    var isServerRunning = false
    var localAddresses: [String] = []
    var log = ""
    var isBusy = false

    private var listener: NWListener?
    private let store: FinanceStore
    /// 会话内已见 nonce（防重放，上限 1024）。
    private var nonceSet: Set<String> = []
    private var nonceOrder: [String] = []
    /// 会话内连续认证失败计数。
    private var authFailureCount = 0
    /// 会话内已接入的对端 device_id（仅日志记录首个出现）。
    private var seenDeviceIDs: Set<String> = []

    init(store: FinanceStore) {
        self.store = store
    }

    var serverURLs: [String] {
        localAddresses.map { "http://\($0):\(portText)" }
    }

    private var normalizedPairingInput: String {
        pairingInput.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    func startServer() {
        guard !isServerRunning, let port = NWEndpoint.Port(portText) else { return }
        pairingCode = LanPairing.shared.generate()
        nonceSet.removeAll()
        nonceOrder.removeAll()
        authFailureCount = 0
        seenDeviceIDs.removeAll()
        do {
            let listener = try NWListener(using: .tcp, on: port)
            listener.newConnectionHandler = { [weak self] connection in
                connection.start(queue: .global(qos: .userInitiated))
                Task { @MainActor in
                    self?.serve(connection)
                }
            }
            listener.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in
                    guard let self else { return }
                    switch state {
                    case .ready:
                        self.isServerRunning = true
                        self.localAddresses = Self.localIPv4Addresses()
                        self.append("服务已启动：端口 \(port.rawValue)，配对码 \(self.pairingCode)")
                    case .failed(let error):
                        self.pairingCode = ""
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
            pairingCode = ""
            append("无法监听端口：\(error.localizedDescription)")
        }
    }

    func stopServer() {
        listener?.cancel()
        listener = nil
        isServerRunning = false
        pairingCode = ""
        nonceSet.removeAll()
        nonceOrder.removeAll()
        authFailureCount = 0
        seenDeviceIDs.removeAll()
        append("服务已停止")
    }

    // MARK: - 客户端（拉取 / 推送）

    func pull() async {
        guard !isBusy else { return }
        let code = normalizedPairingInput
        guard !host.isEmpty, let url = buildSnapshotEndpoint() else {
            append("请先填写对端主机地址")
            return
        }
        guard LanPairing.shared.isValid(code: code) else {
            append("配对码无效：请输入对端接收页面的 10 位配对码")
            return
        }
        isBusy = true
        defer { isBusy = false }
        append("拉取对方快照… \(url.absoluteString)")
        do {
            var request = URLRequest(url: url)
            request.timeoutInterval = 15
            request.setValue(fosProtoHeader, forHTTPHeaderField: "X-FOS-Proto")
            request.setValue(fosDeviceIdentity(), forHTTPHeaderField: "X-FOS-Device-Id")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw LanError("拉取失败：无响应")
            }
            guard http.statusCode == 200 else {
                let fallback = "拉取失败：对方返回 HTTP \(http.statusCode)"
                throw LanError(fosServerErrorText(from: data) ?? fallback)
            }
            let saltHex = http.value(forHTTPHeaderField: "X-FOS-Salt") ?? ""
            guard !saltHex.isEmpty else {
                throw LanError("拉取失败：响应缺少 X-FOS-Salt")
            }
            // 解密/验签在后台线程执行。
            let plain: FosPlainPayload
            do {
                plain = try await fosDetached { try fosDecryptEnvelope(code: code, envelope: data, saltHex: saltHex) }
            } catch {
                throw LanError("配对码错误或数据已损坏")
            }
            // JSON 解码放后台，仅把快照合并回主线程（P2-9）。
            let snapshot = try await fosDetached { try FinanceDataJsonCodec.decode(plain.body) }
            let result = store.applyMerge(snapshot)
            append("拉取完成：新增 \(result.transactionCount) 笔流水、\(result.categoryCount) 个分类、\(result.budgetCount) 条预算")
        } catch {
            append("失败：\(fosReadableClientError(error))")
        }
    }

    func push() async {
        guard !isBusy else { return }
        let code = normalizedPairingInput
        guard !host.isEmpty, let url = buildSnapshotEndpoint() else {
            append("请先填写对端主机地址")
            return
        }
        guard LanPairing.shared.isValid(code: code) else {
            append("配对码无效：请输入对端接收页面的 10 位配对码")
            return
        }
        isBusy = true
        defer { isBusy = false }
        append("推送我的快照… \(url.absoluteString)")
        do {
            let snapshotJSON = try store.exportJSON()
            let salt = LanSyncCrypto.shared.randomBytes(size: fosSaltBytes)
            let iv = LanSyncCrypto.shared.randomBytes(size: fosIVBytes)
            let saltHex = fosHexString(salt)
            let nonceHex = fosHexString(iv)
            let deviceID = fosDeviceIdentity()
            // 密钥派生与加密在后台线程执行。
            let envelope = try await fosDetached { () -> Data in
                let key = try LanSyncCrypto.shared.deriveKey(code: code, salt: salt)
                let payload = LanSyncPayload(
                    proto: Int32(2), alg: fosAlg, ts: fosNowMillis(),
                    deviceId: deviceID, kind: fosKindSnapshotV2, body: snapshotJSON
                )
                let encrypted = try LanSyncCrypto.shared.encrypt(key: key, iv: iv, plaintext: payload.encode(), aad: fosAad())
                return fosData(encrypted)
            }
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.timeoutInterval = 15
            request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            request.setValue(fosProtoHeader, forHTTPHeaderField: "X-FOS-Proto")
            request.setValue(saltHex, forHTTPHeaderField: "X-FOS-Salt")
            request.setValue(nonceHex, forHTTPHeaderField: "X-FOS-Nonce")
            request.setValue(deviceID, forHTTPHeaderField: "X-FOS-Device-Id")
            request.httpBody = envelope
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw LanError("推送失败：无响应")
            }
            guard http.statusCode == 200 else {
                let fallback = "推送失败：对方返回 HTTP \(http.statusCode)"
                throw LanError(fosServerErrorText(from: data) ?? fallback)
            }
            // 响应信封解密（复用请求的 salt）在后台线程执行。
            let plain: FosPlainPayload
            do {
                plain = try await fosDetached { try fosDecryptEnvelope(code: code, envelope: data, saltHex: saltHex) }
            } catch {
                throw LanError("配对码错误或数据已损坏")
            }
            append(fosImportedSummary(plain.body) ?? "推送完成，对方已合并导入")
        } catch {
            append("失败：\(fosReadableClientError(error))")
        }
    }

    private func buildSnapshotEndpoint() -> URL? {
        let urlText = host.hasPrefix("http") ? host : "http://\(host):\(portText)"
        let endpoint = urlText.hasSuffix("/api/snapshot") ? urlText : urlText + "/api/snapshot"
        return URL(string: endpoint)
    }

    // MARK: - 服务端读取（缓冲完整请求后再处理）

    private func serve(_ connection: NWConnection) {
        // 25 秒未形成完整请求则断开，避免悬挂连接。
        let watchdog = Task {
            try? await Task.sleep(nanoseconds: fosRequestTimeoutNanos)
            connection.cancel()
        }
        _ = watchdog
        readRequest(connection: connection, buffer: Data())
    }

    private func readRequest(connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: fosReadChunk) { [weak self] data, _, isComplete, error in
            Task { @MainActor in
                guard let self else {
                    connection.cancel()
                    return
                }
                guard error == nil else {
                    connection.cancel()
                    return
                }
                var accumulated = buffer
                if let data { accumulated.append(data) }
                // 超过 64MB 总长上限直接断开（对应 413）。
                guard accumulated.count <= fosMaxRequestBytes else {
                    self.append("请求体超过 64MB 上限，已断开连接")
                    connection.cancel()
                    return
                }
                if let parsed = LanRequestParser.tryParse(accumulated) {
                    await self.process(parsed, on: connection)
                    return
                }
                if isComplete {
                    self.sendResponse(statusCode: 400, body: fosJsonErrorBody("请求不完整，已关闭连接"), on: connection)
                    return
                }
                self.readRequest(connection: connection, buffer: accumulated)
            }
        }
    }

    // MARK: - 服务端路由（仅在完整请求到达时调用一次）

    private func process(_ request: LanParsedRequest, on connection: NWConnection) async {
        let path = request.path.split(separator: "?", maxSplits: 1).first.map(String.init) ?? request.path
        if request.method == "GET", path == "/api/ping" {
            sendResponse(
                statusCode: 200,
                contentType: "application/json; charset=utf-8",
                body: Data("{\"status\":\"ok\",\"device\":\"FinanceOSiOS\"}".utf8),
                on: connection
            )
            return
        }
        guard (request.method == "GET" || request.method == "POST"), path == "/api/snapshot" else {
            sendResponse(statusCode: 404, body: fosJsonErrorBody("未找到接口：\(request.method) \(path)"), on: connection)
            return
        }
        // 受保护端点：必须声明 X-FOS-Proto:2，否则按旧客户端升级处理。
        guard request.headers["x-fos-proto"] == fosProtoHeader else {
            sendResponse(statusCode: 426, body: Data(LanSyncPolicy.shared.upgradeRequiredBody().utf8), on: connection)
            return
        }
        // 会话未开始（配对码为空）→ 503。
        guard !pairingCode.isEmpty else {
            sendResponse(statusCode: 503, body: fosJsonErrorBody("接收会话未开始，请先开启接收"), on: connection)
            return
        }
        notePeerDeviceIfNew(request.headers["x-fos-device-id"])
        if request.method == "GET" {
            await handleSnapshotGET(on: connection)
        } else {
            await handleSnapshotPOST(request, on: connection)
        }
    }

    // MARK: - 服务端 GET /api/snapshot（加密导出）

    private func handleSnapshotGET(on connection: NWConnection) async {
        do {
            let snapshot = try store.exportJSON()
            let code = pairingCode
            let result = try await fosDetached { try fosBuildSnapshotEnvelope(code: code, snapshotJSON: snapshot) }
            sendResponse(
                statusCode: 200,
                contentType: "application/octet-stream",
                extraHeaders: [("X-FOS-Salt", result.saltHex), ("X-FOS-Nonce", result.nonceHex)],
                body: result.envelope,
                on: connection
            )
        } catch {
            sendResponse(statusCode: 500, body: fosJsonErrorBody("导出失败"), on: connection)
        }
    }

    // MARK: - 服务端 POST /api/snapshot（解密 → 导入 → 加密应答）

    private func handleSnapshotPOST(_ request: LanParsedRequest, on connection: NWConnection) async {
        let code = pairingCode
        let saltHex = request.headers["x-fos-salt"] ?? ""
        let nonce = request.headers["x-fos-nonce"] ?? ""
        let deviceHeader = request.headers["x-fos-device-id"]
        guard !saltHex.isEmpty, !nonce.isEmpty, !request.body.isEmpty else {
            sendResponse(statusCode: 400, body: fosJsonErrorBody("请求缺少 X-FOS-Salt / X-FOS-Nonce 或请求体为空"), on: connection)
            return
        }
        if authFailureCount >= fosMaxAuthFailures {
            sendResponse(statusCode: 429, body: Data(LanSyncPolicy.shared.rateLimitedBody().utf8), on: connection)
            return
        }
        // 解密 + payload 解析失败视为认证失败（后台执行）。
        let payload: FosPlainPayload
        do {
            payload = try await fosDetached { try fosDecryptEnvelope(code: code, envelope: request.body, saltHex: saltHex) }
        } catch {
            authFailureCount += 1
            if authFailureCount >= fosMaxAuthFailures {
                sendResponse(statusCode: 429, body: Data(LanSyncPolicy.shared.rateLimitedBody().utf8), on: connection)
            } else {
                sendResponse(statusCode: 401, body: Data(LanSyncPolicy.shared.authFailedBody().utf8), on: connection)
            }
            return
        }
        notePeerDeviceIfNew(payload.deviceId.isEmpty ? deviceHeader : payload.deviceId)
        // 时间戳新鲜度。
        guard LanSyncPolicy.shared.isTimestampFresh(ts: payload.ts, nowMillis: fosNowMillis()) else {
            sendResponse(statusCode: 400, body: Data(LanSyncPolicy.shared.staleTimestampBody().utf8), on: connection)
            return
        }
        // nonce 防重放（会话内上限 1024）。
        guard recordNonce(nonce) else {
            sendResponse(statusCode: 400, body: fosJsonErrorBody("请求重复（nonce 已使用）"), on: connection)
            return
        }
        // 快照 JSON 解码在后台，导入（合并）回主线程。
        let snapshot: FinanceDataSnapshot
        do {
            snapshot = try await fosDetached { try FinanceDataJsonCodec.decode(payload.body) }
        } catch {
            sendResponse(statusCode: 400, body: fosJsonErrorBody("导入数据解析失败"), on: connection)
            return
        }
        let result = store.applyMerge(snapshot)
        let resultJSON = "{\"imported\":{\"transactions\":\(result.transactionCount),\"categories\":\(result.categoryCount),\"budgets\":\(result.budgetCount)}}"
        do {
            let response = try await fosDetached { try fosBuildImportEnvelope(code: code, saltHex: saltHex, resultJSON: resultJSON) }
            sendResponse(
                statusCode: 200,
                contentType: "application/octet-stream",
                extraHeaders: [("X-FOS-Salt", saltHex), ("X-FOS-Nonce", response.nonceHex)],
                body: response.envelope,
                on: connection
            )
        } catch {
            sendResponse(statusCode: 500, body: fosJsonErrorBody("响应加密失败"), on: connection)
        }
    }

    @discardableResult
    private func recordNonce(_ value: String) -> Bool {
        if nonceSet.contains(value) { return false }
        nonceSet.insert(value)
        nonceOrder.append(value)
        while nonceOrder.count > fosNonceMemory {
            let dropped = nonceOrder.removeFirst()
            nonceSet.remove(dropped)
        }
        return true
    }

    private func notePeerDeviceIfNew(_ deviceID: String?) {
        guard let deviceID, !deviceID.isEmpty else { return }
        if seenDeviceIDs.insert(deviceID).inserted {
            append("新对端接入：\(deviceID)")
        }
    }

    // MARK: - 服务端响应发送

    private func sendResponse(
        statusCode: Int,
        contentType: String = "application/json; charset=utf-8",
        extraHeaders: [(String, String)] = [],
        body: Data,
        on connection: NWConnection
    ) {
        let head = "HTTP/1.1 \(statusCode) \(Self.reasonPhrase(statusCode))\r\n"
            + extraHeaders.map { "\($0.0): \($0.1)\r\n" }.joined()
            + "Content-Type: \(contentType)\r\n"
            + "Content-Length: \(body.count)\r\n"
            + "Connection: close\r\n\r\n"
        var data = Data(head.utf8)
        data.append(body)
        connection.send(content: data, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private static func reasonPhrase(_ statusCode: Int) -> String {
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

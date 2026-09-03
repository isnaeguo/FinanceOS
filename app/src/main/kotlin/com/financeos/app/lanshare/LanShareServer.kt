package com.financeos.app.lanshare

import android.util.Log
import com.financeos.shared.domain.model.FinanceDataImportResult
import com.financeos.shared.lansync.DeviceIdentity
import com.financeos.shared.lansync.Hex
import com.financeos.shared.lansync.LanPairing
import com.financeos.shared.lansync.LanSyncCrypto
import com.financeos.shared.lansync.LanSyncException
import com.financeos.shared.lansync.LanSyncPayload
import com.financeos.shared.lansync.LanSyncPolicy
import com.financeos.shared.lansync.LanSyncSpec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.LinkedHashSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

/** 局域网共享服务默认监听端口，三端一致。 */
internal const val DEFAULT_LAN_SHARE_PORT = 45678

/** /api/ping 中上报的设备名。 */
internal const val LAN_SHARE_DEVICE_NAME = "FinanceOS-Android"

private const val TAG = "LanShareServer"
private const val MAX_REQUEST_HEAD_BYTES = 16 * 1024
private const val MAX_REQUEST_BODY_BYTES = 64 * 1024 * 1024
private const val SOCKET_TIMEOUT_MILLIS = 10_000

private const val CR: Byte = 13
private const val LF: Byte = 10

/** 服务启停后用于驱动页面状态。 */
data class LanShareServerState(
    val running: Boolean,
    val port: Int,
    val addresses: List<String> = emptyList(),
    /** 本次接收会话的配对码；停止接收后清空。 */
    val pairingCode: String = "",
)

private data class HttpRequest(
    val method: String,
    val path: String,
    val contentLength: Int?,
    val proto: String?,
    val saltHex: String?,
    val nonceHex: String?,
    val deviceId: String?,
)

/**
 * FinanceOS 局域网共享服务端（配对加密 v2）。
 *
 * 传输仍为单请求-单响应、Content-Length、无 chunked 的明文 HTTP，但 /api/snapshot 的请求与
 * 响应体均为 AES-256-CBC + HMAC-SHA256 密文（shared 单源实现）；无 X-FOS-Proto 的旧明文访问
 * 返回 426 提示升级。配对码仅本次接收会话有效。
 */
class LanShareServer(
    private val snapshotExporter: suspend () -> String,
    private val snapshotMerger: suspend (String) -> FinanceDataImportResult,
    private val onStateChange: (LanShareServerState) -> Unit = {},
    private val onSnapshotImported: (FinanceDataImportResult) -> Unit = {},
    private val pairingCodeProvider: () -> String? = { null },
    private val onPeerSeen: (String) -> Unit = {},
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var workerScope: CoroutineScope? = null

    /** 会话内已见过的 nonce（LRU），防止同一 IV 重放。 */
    private val seenNonces = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size >= LanSyncSpec.NONCE_MEMORY) {
                val iterator = iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            return super.add(element)
        }
    }

    /** 会话内累计的认证失败次数；达到上限后返回 429。 */
    @Volatile
    private var authFailures = 0

    /** GET 未携带 salt 时生成并复用于同一请求（派生与回写一致）。 */
    @Volatile
    private var lastGeneratedSalt: ByteArray? = null

    val isRunning: Boolean
        get() = serverSocket != null

    /** 监听指定端口；绑定失败或已运行返回 false。 */
    @Synchronized
    fun start(port: Int): Boolean {
        if (serverSocket != null) return true
        val socket = try {
            ServerSocket(port)
        } catch (error: Exception) {
            Log.w(TAG, "监听端口 $port 失败", error)
            onStateChange(LanShareServerState(running = false, port = port))
            return false
        }
        serverSocket = socket
        authFailures = 0
        seenNonces.clear()
        lastGeneratedSalt = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        workerScope = scope
        acceptJob = scope.launch { acceptLoop(socket, scope) }
        onStateChange(
            LanShareServerState(
                running = true,
                port = port,
                addresses = localIpv4Addresses(),
                pairingCode = pairingCodeProvider() ?: "",
            ),
        )
        return true
    }

    @Synchronized
    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        val port = socket.localPort
        try {
            // 关闭监听使阻塞中的 accept 立即返回并清理 Worker 线程。
            socket.close()
        } catch (error: IOException) {
            Log.w(TAG, "关闭监听失败", error)
        }
        acceptJob?.cancel()
        workerScope?.cancel()
        acceptJob = null
        workerScope = null
        onStateChange(LanShareServerState(running = false, port = port))
    }

    private suspend fun acceptLoop(socket: ServerSocket, scope: CoroutineScope) {
        while (scope.isActive) {
            val client = try {
                runInterruptible { socket.accept() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SocketException) {
                break // 监听关闭，正常退出
            } catch (error: IOException) {
                Log.w(TAG, "接受连接失败：${error.message}")
                break
            }
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                val requestHead = readRequestHead(socket.getInputStream())
                val request = requestHead?.let { parseRequest(String(it, Charsets.UTF_8)) }
                if (request == null) {
                    respondJson(socket, "400 Bad Request", jsonError("无法解析 HTTP 请求"))
                    return@use
                }
                respond(request, socket)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 连接异常静默记录，不影响监听循环。
                Log.w(TAG, "处理连接失败：${error.message}")
            }
        }
    }

    private suspend fun respond(request: HttpRequest, socket: Socket) {
        when {
            request.method == "GET" && request.path == "/api/ping" -> {
                // ping 不含业务数据，保持明文以兼容地址探测流程。
                respondJson(
                    socket,
                    "200 OK",
                    JSONObject()
                        .put("status", "ok")
                        .put("device", LAN_SHARE_DEVICE_NAME)
                        .toString(),
                )
            }

            request.method == "GET" && request.path == "/api/snapshot" -> {
                handleSnapshotGet(request, socket)
            }

            request.method == "POST" && request.path == "/api/snapshot" -> {
                handleSnapshotPost(request, socket)
            }

            else -> respondJson(socket, "404 Not Found", jsonError("接口不存在"))
        }
    }

    /** GET /api/snapshot：配对成功时返回加密快照信封，响应头带 salt 与 nonce。 */
    private suspend fun handleSnapshotGet(request: HttpRequest, socket: Socket) {
        val auth = checkPairing(request, socket) ?: return
        noteDevice(request)
        val snapshot = try {
            snapshotExporter()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "导出本机快照失败", error)
            null
        }
        if (snapshot == null) {
            respondJson(socket, "500 Internal Server Error", jsonError("导出本机数据失败"))
            return
        }
        val payload = LanSyncPayload(
            ts = System.currentTimeMillis(),
            deviceId = DeviceIdentity.loadOrCreate(),
            kind = LanSyncPayload.KIND_SNAPSHOT_RESPONSE,
            body = snapshot,
        )
        val envelope = encryptFor(auth, payload.encode())
        if (envelope == null) {
            respondJson(socket, "500 Internal Server Error", jsonError("加密失败"))
        } else {
            respondEnvelope(
                socket,
                "200 OK",
                envelope,
                saltHex = auth.responseSaltHex,
                nonceHex = auth.responseNonceHex,
            )
        }
    }

    /** POST /api/snapshot：解密→合并→加密返回导入结果。 */
    private suspend fun handleSnapshotPost(request: HttpRequest, socket: Socket) {
        val auth = checkPairing(request, socket) ?: return
        noteDevice(request)
        val contentLength = request.contentLength
        if (contentLength == null || contentLength < 0) {
            respondJson(socket, "400 Bad Request", jsonError("缺少 Content-Length"))
            return
        }
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            respondJson(socket, "413 Payload Too Large", jsonError("请求数据过大"))
            return
        }
        val body = readBody(socket, contentLength)
        if (body == null) {
            respondJson(socket, "400 Bad Request", jsonError("读取请求数据失败"))
            return
        }
        if (!seenNonces.add(request.nonceHex.orEmpty())) {
            respondJson(socket, "400 Bad Request", jsonError("重复的请求标识"))
            return
        }
        val plain = try {
            LanSyncCrypto.decrypt(auth.key, body, LanSyncSpec.PROTO.toString().toByteArray())
        } catch (error: LanSyncException) {
            recordAuthFailure(socket)
            return
        }
        val payload = try {
            LanSyncPayload.decode(plain)
        } catch (error: LanSyncException) {
            recordAuthFailure(socket)
            return
        }
        if (!LanSyncPolicy.isTimestampFresh(payload.ts, System.currentTimeMillis())) {
            respondJson(socket, "400 Bad Request", LanSyncPolicy.staleTimestampBody())
            return
        }
        var failure: Throwable? = null
        val result = try {
            snapshotMerger(payload.body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "合并导入失败", error)
            failure = error
            null
        }
        if (result == null) {
            respondJson(
                socket,
                "400 Bad Request",
                jsonError(importErrorMessage(failure)),
            )
            return
        }
        val responsePayload = LanSyncPayload(
            ts = System.currentTimeMillis(),
            deviceId = DeviceIdentity.loadOrCreate(),
            kind = LanSyncPayload.KIND_IMPORT_RESULT,
            body = importedJson(result),
        )
        val responseEnvelope = encryptFor(auth, responsePayload.encode())
        if (responseEnvelope == null) {
            respondJson(socket, "500 Internal Server Error", jsonError("加密失败"))
        } else {
            respondEnvelope(
                socket,
                "200 OK",
                responseEnvelope,
                saltHex = auth.responseSaltHex,
                nonceHex = auth.responseNonceHex,
            )
            onSnapshotImported(result)
        }
    }

    /** 请求头里的加密参数是否可进入业务处理；不满足时已写响应并返回 null。 */
    private fun checkPairing(request: HttpRequest, socket: Socket): SessionAuth? {
        if (authFailures >= LanSyncSpec.MAX_AUTH_FAILURES) {
            respondJson(socket, "429 Too Many Requests", LanSyncPolicy.rateLimitedBody())
            return null
        }
        if (request.proto != LanSyncSpec.PROTO.toString()) {
            respondJson(socket, "426 Upgrade Required", LanSyncPolicy.upgradeRequiredBody())
            return null
        }
        val pairingCode = pairingCodeProvider()
        if (pairingCode.isNullOrBlank()) {
            respondJson(socket, "503 Service Unavailable", jsonError("接收会话尚未开始，请先开启共享服务"))
            return null
        }
        if (!LanPairing.isValid(pairingCode)) {
            respondJson(socket, "500 Internal Server Error", jsonError("服务端配对码无效"))
            return null
        }
        val key = try {
            LanSyncCrypto.deriveKey(pairingCode, saltFor(request))
        } catch (error: Exception) {
            Log.w(TAG, "密钥派生失败", error)
            respondJson(socket, "500 Internal Server Error", jsonError("密钥派生失败"))
            return null
        }
        // GET 未携带 salt 时服务端生成并在响应头回写；POST 客户端必带 salt。
        val responseSaltHex = request.saltHex?.takeIf { it.isNotBlank() } ?: Hex.encode(saltFor(request))
        return SessionAuth(key = key, responseSaltHex = responseSaltHex)
    }

    private fun saltFor(request: HttpRequest): ByteArray {
        val salt = request.saltHex?.let { Hex.decode(it) }
        if (salt != null && salt.size == LanSyncSpec.SALT_BYTES) return salt
        val existing = lastGeneratedSalt
        if (existing != null && existing.size == LanSyncSpec.SALT_BYTES) return existing
        val generated = LanSyncCrypto.randomBytes(LanSyncSpec.SALT_BYTES)
        lastGeneratedSalt = generated
        return generated
    }

    private class SessionAuth(
        val key: ByteArray,
        val responseSaltHex: String?,
    ) {
        var responseNonceHex: String? = null
    }

    /** 以本次请求派生密钥加密；信封的 iv 记录到 [SessionAuth.responseNonceHex]。 */
    private fun encryptFor(auth: SessionAuth, plaintext: ByteArray): ByteArray? {
        val iv = LanSyncCrypto.randomBytes(LanSyncSpec.IV_BYTES)
        auth.responseNonceHex = Hex.encode(iv)
        return try {
            LanSyncCrypto.encrypt(auth.key, iv, plaintext, LanSyncSpec.PROTO.toString().toByteArray())
        } catch (error: Exception) {
            Log.w(TAG, "加密失败", error)
            null
        }
    }

    private fun recordAuthFailure(socket: Socket) {
        authFailures += 1
        respondJson(socket, "401 Unauthorized", LanSyncPolicy.authFailedBody())
    }

    private fun noteDevice(request: HttpRequest) {
        val deviceId = request.deviceId
        if (!deviceId.isNullOrBlank()) {
            onPeerSeen(deviceId)
        }
    }
}

private fun importedJson(result: FinanceDataImportResult): String = JSONObject()
    .put(
        "imported",
        JSONObject()
            .put("transactions", result.transactionCount)
            .put("categories", result.categoryCount)
            .put("budgets", result.budgetCount),
    )
    .toString()

private fun jsonError(message: String): String = JSONObject().put("error", message).toString()

private fun importErrorMessage(error: Throwable?): String = when (error) {
    is IllegalArgumentException -> error.message ?: "导入数据不合法"
    else -> "导入失败，请检查数据格式"
}

/** 从请求头中读取方法、路径与加密相关头。 */
private fun parseRequest(headText: String): HttpRequest? {
    val lines = headText.split("\r\n")
    val requestLine = lines.firstOrNull()?.trim() ?: return null
    val parts = requestLine.split(" ")
    if (parts.size < 2) return null
    var contentLength: Int? = null
    var proto: String? = null
    var saltHex: String? = null
    var nonceHex: String? = null
    var deviceId: String? = null
    for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        when {
            name.equals("Content-Length", ignoreCase = true) -> contentLength = value.toIntOrNull()
            name.equals(LanSyncSpec.HEADER_PROTO, ignoreCase = true) -> proto = value
            name.equals(LanSyncSpec.HEADER_SALT, ignoreCase = true) -> saltHex = value
            name.equals(LanSyncSpec.HEADER_NONCE, ignoreCase = true) -> nonceHex = value
            name.equals(LanSyncSpec.HEADER_DEVICE_ID, ignoreCase = true) -> deviceId = value
        }
    }
    return HttpRequest(
        method = parts[0].uppercase(),
        path = parts[1].substringBefore('?'),
        contentLength = contentLength,
        proto = proto,
        saltHex = saltHex,
        nonceHex = nonceHex,
        deviceId = deviceId,
    )
}

/** 逐块读取直到 \r\n\r\n，只按需累积且不会把 body 提前吞进缓冲。 */
private fun readRequestHead(input: InputStream): ByteArray? {
    val buffer = ByteArrayOutputStream(256)
    val chunk = ByteArray(1024)
    while (buffer.size() < MAX_REQUEST_HEAD_BYTES) {
        val count = input.read(chunk)
        if (count < 0) break
        val previousSize = buffer.size()
        buffer.write(chunk, 0, count)
        val data = buffer.toByteArray()
        val searchStart = (previousSize - 3).coerceAtLeast(0)
        val searchEnd = data.size - 4
        for (index in searchStart..searchEnd) {
            if (data[index] == CR &&
                data[index + 1] == LF &&
                data[index + 2] == CR &&
                data[index + 3] == LF
            ) {
                return data.copyOf(index + 4)
            }
        }
    }
    return null
}

private fun readBody(socket: Socket, length: Int): ByteArray? {
    val input = socket.getInputStream()
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = input.read(bytes, offset, length - offset)
        if (count < 0) return null
        offset += count
    }
    return bytes
}

/** 写固定长度 JSON 响应；不使用 chunked。 */
private fun respondJson(socket: Socket, status: String, json: String) {
    val bodyBytes = json.toByteArray(Charsets.UTF_8)
    val head = buildString {
        append("HTTP/1.1 ").append(status).append("\r\n")
        append("Content-Type: application/json\r\n")
        append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }
    val output = socket.getOutputStream()
    output.write(head.toByteArray(Charsets.US_ASCII))
    output.write(bodyBytes)
    output.flush()
}

/** 写加密信封响应；按需携带 X-FOS-Salt 与 X-FOS-Nonce。 */
private fun respondEnvelope(
    socket: Socket,
    status: String,
    envelope: ByteArray,
    saltHex: String? = null,
    nonceHex: String? = null,
) {
    val head = buildString {
        append("HTTP/1.1 ").append(status).append("\r\n")
        append("Content-Type: application/octet-stream\r\n")
        append("Content-Length: ").append(envelope.size).append("\r\n")
        if (!saltHex.isNullOrBlank()) {
            append(LanSyncSpec.HEADER_SALT).append(": ").append(saltHex).append("\r\n")
        }
        if (!nonceHex.isNullOrBlank()) {
            append(LanSyncSpec.HEADER_NONCE).append(": ").append(nonceHex).append("\r\n")
        }
        append("Connection: close\r\n")
        append("\r\n")
    }
    val output = socket.getOutputStream()
    output.write(head.toByteArray(Charsets.US_ASCII))
    output.write(envelope)
    output.flush()
}

/** 返回本机 site-local IPv4 地址，例如 192.168.x.x。 */
internal fun localIpv4Addresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces()
        .toList()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filter { it is Inet4Address && it.isSiteLocalAddress }
        .map { it.hostAddress }
        .filterNotNull()
        .distinct()
        .sorted()
        .toList()
} catch (error: Exception) {
    emptyList()
}

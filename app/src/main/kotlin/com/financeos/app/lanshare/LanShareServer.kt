package com.financeos.app.lanshare

import android.util.Log
import com.financeos.shared.domain.model.FinanceDataImportResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
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

/** 局域网共享服务默认监听端口，与 macOS 端同一明文 HTTP 协议。 */
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
)

private data class HttpRequest(
    val method: String,
    val path: String,
    val contentLength: Int?,
)

/**
 * FinanceOS 局域网共享服务端。
 *
 * 不使用任何第三方网络库：在 Dispatchers.IO 上运行 kotlinx-coroutines + [ServerSocket]，
 * 每个已接受的连接在独立协程中解析并响应。响应一律带 Content-Length，不启用 chunked。
 *
 * 协议：
 *  - GET  /api/ping      → 200 {"status":"ok","device":"FinanceOS-Android"}
 *  - GET  /api/snapshot  → 200 financeos-backup JSON
 *  - POST /api/snapshot  → 200 {"imported":{...}}；失败 400 {"error":"..."}
 */
class LanShareServer(
    private val snapshotExporter: suspend () -> String,
    private val snapshotMerger: suspend (String) -> FinanceDataImportResult,
    private val onStateChange: (LanShareServerState) -> Unit = {},
    private val onSnapshotImported: (FinanceDataImportResult) -> Unit = {},
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var workerScope: CoroutineScope? = null

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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        workerScope = scope
        acceptJob = scope.launch { acceptLoop(socket, scope) }
        onStateChange(
            LanShareServerState(
                running = true,
                port = port,
                addresses = localIpv4Addresses(),
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
                } else {
                    respondJson(socket, "200 OK", snapshot)
                }
            }

            request.method == "POST" && request.path == "/api/snapshot" -> {
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
                var failure: Throwable? = null
                val result = try {
                    snapshotMerger(String(body, Charsets.UTF_8))
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
                respondJson(socket, "200 OK", importedJson(result))
                onSnapshotImported(result)
            }

            else -> respondJson(socket, "404 Not Found", jsonError("接口不存在"))
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

/** 从请求头中读取方法、路径和 Content-Length。 */
private fun parseRequest(headText: String): HttpRequest? {
    val lines = headText.split("\r\n")
    val requestLine = lines.firstOrNull()?.trim() ?: return null
    val parts = requestLine.split(" ")
    if (parts.size < 2) return null
    var contentLength: Int? = null
    for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        if (line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
            contentLength = line.substring(colon + 1).trim().toIntOrNull()
        }
    }
    return HttpRequest(
        method = parts[0].uppercase(),
        path = parts[1].substringBefore('?'),
        contentLength = contentLength,
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

/** 写固定长度响应；不使用 chunked。 */
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

package com.financeos.app.lanshare

import com.financeos.shared.domain.model.FinanceDataImportResult
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 10_000
private const val SNAPSHOT_PATH = "/api/snapshot"

/** 局域网共享操作失败时携带中文提示的可展示异常。 */
class LanShareClientException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 访问同一明文 HTTP 协议对端的简单客户端：拉取快照 / 推送快照。 */
object LanShareClient {
    /** 从对方设备 GET /api/snapshot，返回 financeos-backup JSON 文本。 */
    suspend fun pullSnapshot(host: String, port: Int): String = withContext(Dispatchers.IO) {
        try {
            val connection = openConnection(host, port, SNAPSHOT_PATH)
            try {
                connection.requestMethod = "GET"
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    readFully(connection.inputStream)
                } else {
                    throw IOException(httpErrorMessage(connection, code, "拉取对方快照失败"))
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw error.friendly(host, port)
        }
    }

    /** 向对方设备 POST /api/snapshot 推送本机完整快照，返回对方合并导入的笔数。 */
    suspend fun pushSnapshot(host: String, port: Int, json: String): FinanceDataImportResult =
        withContext(Dispatchers.IO) {
            try {
                val body = json.toByteArray(Charsets.UTF_8)
                val connection = openConnection(host, port, SNAPSHOT_PATH)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                // 固定长度上传保证对方能通过 Content-Length 读到完整 body。
                connection.setFixedLengthStreamingMode(body.size)
                try {
                    connection.outputStream.use { output -> output.write(body) }
                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_OK) {
                        parseImportedResponse(readFully(connection.inputStream))
                    } else {
                        throw IOException(httpErrorMessage(connection, code, "推送本机快照失败"))
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw error.friendly(host, port)
            }
        }

    private fun openConnection(host: String, port: Int, path: String): HttpURLConnection {
        val url = URL("http://$host:$port$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        return connection
    }

    private fun readFully(stream: InputStream): String =
        stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }

    private fun parseImportedResponse(body: String): FinanceDataImportResult {
        val imported = JSONObject(body).getJSONObject("imported")
        return FinanceDataImportResult(
            transactionCount = imported.getInt("transactions"),
            categoryCount = imported.getInt("categories"),
            budgetCount = imported.getInt("budgets"),
        )
    }

    private fun httpErrorMessage(connection: HttpURLConnection, code: Int, action: String): String {
        val body = try {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (error: Exception) {
            null
        }
        return if (body.isNullOrBlank()) {
            "$action（HTTP $code）"
        } else {
            "$action（HTTP $code）：${errorMessageFrom(body)}"
        }
    }

    /** 从 {"error":"..."} 中取出中文错误，取不到时保留响应片段以便排查。 */
    private fun errorMessageFrom(body: String): String = try {
        val message = JSONObject(body).optString("error")
        if (message.isNotBlank()) message else body.take(120)
    } catch (error: Exception) {
        body.take(120)
    }
}

private fun Exception.friendly(host: String, port: Int): LanShareClientException = when (this) {
    is SocketTimeoutException ->
        LanShareClientException("连接超时：请确认对方设备在同一局域网，且共享服务已启动。", this)

    is ConnectException ->
        LanShareClientException("无法连接到 $host:$port：请检查 IP 与端口，对方可能未启动共享服务。", this)

    is UnknownHostException ->
        LanShareClientException("无法解析主机地址 $host：请检查输入是否正确。", this)

    is LanShareClientException -> this

    else -> LanShareClientException(
        message ?: "网络操作失败。",
        this,
    )
}

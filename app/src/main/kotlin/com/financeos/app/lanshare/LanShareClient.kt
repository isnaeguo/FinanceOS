package com.financeos.app.lanshare

import com.financeos.shared.domain.model.FinanceDataImportResult
import com.financeos.shared.lansync.DeviceIdentity
import com.financeos.shared.lansync.Hex
import com.financeos.shared.lansync.LanPairing
import com.financeos.shared.lansync.LanSyncCrypto
import com.financeos.shared.lansync.LanSyncException
import com.financeos.shared.lansync.LanSyncPayload
import com.financeos.shared.lansync.LanSyncSpec
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
private const val READ_TIMEOUT_MILLIS = 20_000
private const val SNAPSHOT_PATH = "/api/snapshot"

/** 局域网共享操作失败时携带中文提示的可展示异常。 */
class LanShareClientException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 访问配对加密协议对端的简单客户端：拉取快照 / 推送快照。 */
object LanShareClient {
    /**
     * 从对方设备拉取加密快照，返回 financeos-backup JSON 文本。
     *
     * @throws LanShareClientException 网络失败、配对码错误、对端版本过旧或过于频繁时。
     */
    suspend fun pullSnapshot(host: String, port: Int, pairingCode: String): String =
        withContext(Dispatchers.IO) {
            try {
                val exchange = encryptedExchange(host, port, pairingCode)
                val connection = exchange.connection
                connection.requestMethod = "GET"
                addCryptoHeaders(connection, exchange)
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    val envelope = readFullyBytes(connection.inputStream)
                    val payload = exchange.decryptResponse(envelope)
                    payload.body
                } else {
                    throw IOException(httpErrorMessage(connection, code, "拉取对方快照失败"))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw error.friendly(host, port)
            }
        }

    /** 向对方设备 POST /api/snapshot 推送加密快照，返回对方合并导入的笔数。 */
    suspend fun pushSnapshot(host: String, port: Int, pairingCode: String, json: String): FinanceDataImportResult =
        withContext(Dispatchers.IO) {
            try {
                val exchange = encryptedExchange(host, port, pairingCode)
                val connection = exchange.connection
                connection.requestMethod = "POST"
                connection.doOutput = true
                val payload = LanSyncPayload(
                    ts = System.currentTimeMillis(),
                    deviceId = DeviceIdentity.loadOrCreate(),
                    kind = LanSyncPayload.KIND_SNAPSHOT_V2,
                    body = json,
                )
                val envelope = exchange.encrypt(payload.encode())
                addCryptoHeaders(connection, exchange)
                connection.setFixedLengthStreamingMode(envelope.size)
                try {
                    connection.outputStream.use { output -> output.write(envelope) }
                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_OK) {
                        val responseEnvelope = readFullyBytes(connection.inputStream)
                        val responsePayload = exchange.decryptResponse(responseEnvelope)
                        parseImportedResponse(responsePayload.body)
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

    /** 一次加密交换的共享状态：连接 + 本请求随机 salt 派生的密钥 + 本次 iv。 */
    private class EncryptedExchange(
        val connection: HttpURLConnection,
        pairingCode: String,
    ) {
        val salt: ByteArray = LanSyncCrypto.randomBytes(LanSyncSpec.SALT_BYTES)
        val iv: ByteArray = LanSyncCrypto.randomBytes(LanSyncSpec.IV_BYTES)
        val saltHex: String = Hex.encode(salt)
        val ivHex: String = Hex.encode(iv)
        private val key: ByteArray = LanSyncCrypto.deriveKey(pairingCode, salt)

        fun encrypt(plaintext: ByteArray): ByteArray =
            LanSyncCrypto.encrypt(key, iv, plaintext, LanSyncSpec.PROTO.toString().toByteArray())

        fun decryptResponse(envelope: ByteArray): LanSyncPayload {
            val plain = LanSyncCrypto.decrypt(key, envelope, LanSyncSpec.PROTO.toString().toByteArray())
            return LanSyncPayload.decode(plain)
        }

        fun headers(): Map<String, String> = mapOf(
            LanSyncSpec.HEADER_PROTO to LanSyncSpec.PROTO.toString(),
            LanSyncSpec.HEADER_SALT to saltHex,
            LanSyncSpec.HEADER_NONCE to ivHex,
            LanSyncSpec.HEADER_DEVICE_ID to DeviceIdentity.loadOrCreate(),
        )
    }

    private fun encryptedExchange(host: String, port: Int, pairingCode: String): EncryptedExchange {
        val code = pairingCode.trim()
        if (!LanPairing.isValid(code)) {
            throw LanShareClientException("配对码格式不正确，请输入 10 位大写字母与数字（不含 0/O/1/I）。")
        }
        val url = URL("http://$host:$port$SNAPSHOT_PATH")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        return try {
            EncryptedExchange(connection, code)
        } catch (error: LanSyncException) {
            connection.disconnect()
            throw LanShareClientException("密钥派生失败。", error)
        }
    }

    private fun addCryptoHeaders(connection: HttpURLConnection, exchange: EncryptedExchange) {
        exchange.headers().forEach { (name, value) ->
            connection.setRequestProperty(name, value)
        }
        connection.setRequestProperty("Content-Type", "application/octet-stream")
    }

    private fun readFullyBytes(stream: InputStream): ByteArray =
        stream.use { input -> input.readBytes() }

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
        val detail = if (body.isNullOrBlank()) "" else ": ${errorMessageFrom(body)}"
        return "$action（HTTP $code）$detail"
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

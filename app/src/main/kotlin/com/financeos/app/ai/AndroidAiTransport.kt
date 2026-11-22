package com.financeos.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.financeos.shared.ai.AiApiProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyStore
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AiRequestException(message: String) : Exception(message)

internal class AiConnection(
    val endpoint: URI,
    val model: String,
    val apiKey: String,
) {
    val protocol: AiApiProtocol get() = aiProtocol(endpoint)
}

/** 密文保存在禁止备份的应用目录，AES 密钥由 Android Keystore 管理。 */
internal class AiConnectionStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "finance-ai.properties"))

    @Synchronized
    fun load(): AiConnection? {
        val properties = Properties()
        try {
            file.openRead().use(properties::load)
        } catch (_: java.io.FileNotFoundException) {
            return null
        }
        val endpoint = aiEndpoint(properties.getProperty("endpoint").orEmpty())
        val model = properties.getProperty("model").orEmpty()
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(CIPHER)
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("AI credential key is missing.")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decoder.decode(properties.getProperty("iv"))))
        cipher.updateAAD(associatedData(endpoint, model))
        val apiKey = cipher.doFinal(decoder.decode(properties.getProperty("credential"))).toString(UTF_8)
        return AiConnection(endpoint, model, apiKey)
    }

    @Synchronized
    fun save(connection: AiConnection) {
        val store = keyStore()
        val key = store.getKey(KEY_ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData(connection.endpoint, connection.model))
        val encoder = Base64.getEncoder()
        val properties = Properties().apply {
            setProperty("endpoint", connection.endpoint.toASCIIString())
            setProperty("model", connection.model)
            setProperty("iv", encoder.encodeToString(cipher.iv))
            setProperty("credential", encoder.encodeToString(cipher.doFinal(connection.apiKey.toByteArray(UTF_8))))
        }
        val stream = file.startWrite()
        try {
            properties.store(stream, null)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun clear() {
        keyStore().deleteEntry(KEY_ALIAS)
        file.delete()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun associatedData(endpoint: URI, model: String): ByteArray =
        "${endpoint.toASCIIString()}\n$model".toByteArray(UTF_8)

    private companion object {
        const val KEY_ALIAS = "financeos.ai.connection"
        const val CIPHER = "AES/GCM/NoPadding"
    }
}

internal fun aiEndpoint(value: String): URI {
    val input = value.trim()
    val error = "请输入 HTTPS 接口地址，例如 https://api.openai.com/v1。地址不能包含凭据、查询或片段。"
    val uri = try {
        URI(input)
    } catch (_: java.net.URISyntaxException) {
        throw IllegalArgumentException(error)
    }
    require(
        input.length <= 2_048 && uri.scheme.equals("https", ignoreCase = true) &&
            uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65_535),
    ) { error }
    val path = uri.rawPath.trimEnd('/')
    val requestPath = when {
        path.endsWith("/chat/completions") || path.endsWith("/responses") -> path
        path.isEmpty() -> "/v1/responses"
        else -> "$path/responses"
    }
    return URI.create("https://${uri.rawAuthority}$requestPath").normalize()
}

internal fun aiProtocol(endpoint: URI): AiApiProtocol =
    if (endpoint.rawPath.endsWith("/chat/completions")) AiApiProtocol.CHAT_COMPLETIONS
    else AiApiProtocol.RESPONSES


internal class AndroidAiTransport {
    suspend fun complete(connection: AiConnection, body: String): String = try {
        withTimeout(60_000) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    val http = connection.endpoint.toURL().openConnection() as HttpsURLConnection
                    continuation.invokeOnCancellation {
                        Dispatchers.IO.dispatch(EmptyCoroutineContext) { http.disconnect() }
                    }
                    try {
                        if (continuation.isActive) continuation.resume(exchange(http, connection.apiKey, body))
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    } finally {
                        http.disconnect()
                    }
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw AiRequestException("AI 接口响应超时，请重试。")
    } catch (_: SocketTimeoutException) {
        throw AiRequestException("AI 接口响应超时，请重试。")
    }

    private fun exchange(http: HttpsURLConnection, apiKey: String, body: String): String {
        http.instanceFollowRedirects = false
        http.requestMethod = "POST"
        http.connectTimeout = 15_000
        http.readTimeout = 30_000
        http.doOutput = true
        if (apiKey.isNotEmpty()) http.setRequestProperty("Authorization", "Bearer $apiKey")
        http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        http.setRequestProperty("Accept", "application/json")
        val payload = body.toByteArray(UTF_8)
        http.setFixedLengthStreamingMode(payload.size)
        http.outputStream.use { it.write(payload) }
        when (val status = http.responseCode) {
            in 200..299 -> Unit
            401, 403 -> throw AiRequestException("AI 接口拒绝授权，请核对密钥和模型权限。")
            429 -> throw AiRequestException("AI 接口限流或额度不足，请检查服务设置。")
            in 300..399 -> throw AiRequestException("AI 接口要求重定向，请改用服务提供方的最终 HTTPS 地址。")
            404 -> throw AiRequestException("接口地址或模型不可用，请核对服务的完整路径和模型名称。")
            else -> throw AiRequestException("AI 接口返回 HTTP $status，请核对接口地址和模型。")
        }
        http.inputStream.use { input ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (result.size() + count > MAX_RESPONSE_BYTES) {
                    throw AiRequestException("AI 响应超过大小限制。")
                }
                result.write(buffer, 0, count)
            }
            return result.toString(UTF_8.name())
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}

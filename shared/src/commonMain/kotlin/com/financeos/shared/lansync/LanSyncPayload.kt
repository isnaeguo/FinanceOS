package com.financeos.shared.lansync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 局域网同步加密前的明文 payload（JSON），字段名与 docs/lan-sync-protocol.md 一致：
 * `{ "proto": 2, "alg": "AES-256-CBC+HMAC-SHA256", "ts": <epochMillis>, "device_id": "<uuid>",
 *   "kind": "<kind>", "body": "<financeos-backup JSON 字符串>" }`
 */
@Serializable
data class LanSyncPayload(
    val proto: Int = LanSyncSpec.PROTO,
    val alg: String = LanSyncSpec.ALG,
    val ts: Long,
    @SerialName("device_id")
    val deviceId: String,
    val kind: String,
    val body: String,
) {
    /** 序列化为加密前的明文字节。 */
    fun encode(): ByteArray = json.encodeToString(LanSyncPayload.serializer(), this).encodeToByteArray()

    companion object {
        /** 请求方向（推送快照 / 应答导入结果）的 kind。 */
        const val KIND_SNAPSHOT_V2 = "snapshot_v2"

        /** GET 快照响应方向的 kind（body 为对端完整快照）。 */
        const val KIND_SNAPSHOT_RESPONSE = "snapshot_response"

        /** POST 推送后服务端应答的 kind（body 为 {"imported":{...}} JSON）。 */
        const val KIND_IMPORT_RESULT = "import_result"

        private val json = Json { ignoreUnknownKeys = true }

        /** 从字节解析；proto/alg 校验失败抛 [LanSyncException]。 */
        fun decode(bytes: ByteArray): LanSyncPayload {
            val payload = try {
                json.decodeFromString(LanSyncPayload.serializer(), bytes.decodeToString())
            } catch (error: Exception) {
                throw LanSyncException("加密载荷解析失败。", error)
            }
            if (payload.proto != LanSyncSpec.PROTO) throw LanSyncException("不支持的加密协议版本：${payload.proto}。")
            if (payload.alg != LanSyncSpec.ALG) throw LanSyncException("不支持的加密算法：${payload.alg}。")
            return payload
        }
    }
}

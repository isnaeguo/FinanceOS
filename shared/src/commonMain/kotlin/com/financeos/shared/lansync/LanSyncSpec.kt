package com.financeos.shared.lansync

/**
 * 局域网同步加密协议常量（v2）。三端 HTTP 头与 payload 字段名称在此单源定义，
 * 各端实现与文档（docs/lan-sync-protocol.md）都以此为准。
 *
 * 残余风险（详见协议文档）：元数据（IP/端口/包长）仍可见；本方案不做服务器身份认证，
 * 配对码熵即安全上界；不做前向保密。
 */
object LanSyncSpec {
    /** 协议版本，写入 payload 的 proto 字段与请求头 X-FOS-Proto。 */
    const val PROTO = 2

    /** payload.alg 字段：全端统一的 AEAD 组合（见算法选型说明）。 */
    const val ALG = "AES-256-CBC+HMAC-SHA256"

    const val HEADER_PROTO = "X-FOS-Proto"
    const val HEADER_SALT = "X-FOS-Salt"
    const val HEADER_NONCE = "X-FOS-Nonce"
    const val HEADER_DEVICE_ID = "X-FOS-Device-Id"

    /** PBKDF2-HMAC-SHA256 迭代次数。 */
    const val KDF_ITERATIONS = 150_000

    /** KDF salt 长度（字节）。 */
    const val SALT_BYTES = 16

    /** AES-CBC IV 长度（字节）。 */
    const val IV_BYTES = 16

    /** HMAC-SHA256 摘要长度（字节）。 */
    const val MAC_BYTES = 32

    /** 派生密钥长度（字节，256 bit）。 */
    const val KEY_BYTES = 32

    /** 时间戳容忍窗口：|server_now - ts| 超过该毫秒数即拒绝。 */
    const val TS_TOLERANCE_MILLIS = 5 * 60 * 1000L

    /** 单会话内记忆的 nonce 上限（LRU 淘汰）。 */
    const val NONCE_MEMORY = 1024

    /** 同一来源连续认证失败达到该次数后返回 429。 */
    const val MAX_AUTH_FAILURES = 5

    /** 请求头最大字节数。 */
    const val MAX_HEAD_BYTES = 16 * 1024

    /** 请求体最大字节数（快照明文最大约 64MB，加密开销在其上）。 */
    const val MAX_BODY_BYTES = 64 * 1024 * 1024
}

package com.financeos.shared.lansync

import kotlin.Throws

/**
 * 局域网同步加密原语（expect）：密钥派生、AES-256-CBC + HMAC-SHA256（Encrypt-then-MAC）、随机字节。
 *
 * 算法选型说明：Apple 公开 API 不提供 AES-GCM（CommonCrypto 仅暴露 CBC），而跨端一致性测试
 * 要求给定参数密文逐字节一致，因此三端统一使用 AES-256-CBC + HMAC-SHA256：
 * 先加密后签名（EtM），MAC 覆盖 IV‖密文‖AAD，payload `alg` 字段如实声明。
 *
 * 传输信封格式：`envelope = iv(16) ‖ mac(32) ‖ ciphertext`，iv 同时经请求/响应头 X-FOS-Nonce
 * 以十六进制明文携带（iv 本身不是秘密）。
 */
expect object LanSyncCrypto {
    /** 平台安全随机源生成 [size] 字节。 */
    fun randomBytes(size: Int): ByteArray

    /**
     * PBKDF2-HMAC-SHA256 派生 256 bit 密钥。
     *
     * @throws LanSyncException 派生失败时。
     */
    @Throws(LanSyncException::class)
    fun deriveKey(code: String, salt: ByteArray): ByteArray

    /**
     * 用显式 [iv] 加密（确定性路径，供固定向量测试与 GET 响应侧生成后下发）。
     * 返回 `iv ‖ mac(32) ‖ ciphertext` 信封。
     *
     * @throws LanSyncException 加密失败时。
     */
    @Throws(LanSyncException::class)
    fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /**
     * 解密并校验信封；MAC 校验失败或格式非法时抛 [LanSyncException]（调用方映射为 401）。
     */
    @Throws(LanSyncException::class)
    fun decrypt(key: ByteArray, envelope: ByteArray, aad: ByteArray): ByteArray
}

/** 配对加密失败的可展示异常；解密/验签失败时服务端据此返回 401。 */
class LanSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

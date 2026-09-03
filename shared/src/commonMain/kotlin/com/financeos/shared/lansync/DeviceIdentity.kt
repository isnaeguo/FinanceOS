package com.financeos.shared.lansync

/**
 * 本机持久化设备标识（UUID v4），随每次加密请求经 `X-FOS-Device-Id` 上报。
 *
 * UUID 生成逻辑在 common 单源（随机字节 + v4 位设置），三端一致；持久化介质由各端 actual 提供：
 * Android 用 SharedPreferences（经 [AppContextHolder]），Apple 用 NSUserDefaults，
 * JVM（测试）用进程内缓存。Keychain / EncryptedSharedPreferences 留作后续加固。
 */
object DeviceIdentity {
    private const val PREFERENCE_KEY = "financeos.device_id"

    /** 读取已持久化的标识；不存在时生成并保存。 */
    fun loadOrCreate(): String {
        DeviceIdentityStorage.read()?.let { return it }
        val created = newUuidV4()
        DeviceIdentityStorage.write(created)
        return created
    }

    internal fun preferenceKey(): String = PREFERENCE_KEY

    private fun newUuidV4(): String {
        val bytes = LanSyncCrypto.randomBytes(16)
        // RFC 4122：version=4、variant=10。
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = Hex.encode(bytes)
        return buildString(36) {
            append(hex, 0, 8)
            append('-')
            append(hex, 8, 12)
            append('-')
            append(hex, 12, 16)
            append('-')
            append(hex, 16, 20)
            append('-')
            append(hex, 20, 32)
        }
    }
}

/** 设备标识的持久化介质（按平台注入）。 */
expect object DeviceIdentityStorage {
    fun read(): String?
    fun write(value: String)
}

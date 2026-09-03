package com.financeos.shared.lansync

/** JVM（测试/桌面）实现：进程内缓存，便于 commonTest 断言格式一致。 */
actual object DeviceIdentityStorage {
    private var cached: String? = null

    actual fun read(): String? = cached

    actual fun write(value: String) {
        cached = value
    }
}

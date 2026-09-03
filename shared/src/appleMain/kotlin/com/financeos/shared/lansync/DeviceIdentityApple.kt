package com.financeos.shared.lansync

import platform.Foundation.NSUserDefaults

/** Apple 实现：NSUserDefaults 持久化。 */
actual object DeviceIdentityStorage {
    actual fun read(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(DeviceIdentity.preferenceKey())

    actual fun write(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = DeviceIdentity.preferenceKey())
    }
}

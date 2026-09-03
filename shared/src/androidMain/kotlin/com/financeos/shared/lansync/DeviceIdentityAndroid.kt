package com.financeos.shared.lansync

import android.content.Context

/** Android 实现：SharedPreferences 持久化。 */
actual object DeviceIdentityStorage {
    private fun preferences(): android.content.SharedPreferences =
        AppContextHolder.requireContext().getSharedPreferences(
            "financeos_lansync",
            Context.MODE_PRIVATE,
        )

    actual fun read(): String? = preferences().getString(DeviceIdentity.preferenceKey(), null)

    actual fun write(value: String) {
        preferences().edit().putString(DeviceIdentity.preferenceKey(), value).apply()
    }
}

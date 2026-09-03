package com.financeos.shared.lansync

import android.content.Context

/**
 * Android 平台 Application context 暂存点：shared 无法直接拿到 Context，
 * 由 App 的 Application.onCreate 注入一次；未注入前任何需要该上下文的调用都会失败。
 */
object AppContextHolder {
    @Volatile
    var context: Context? = null

    internal fun requireContext(): Context = checkNotNull(context) {
        "AppContextHolder 尚未初始化：请在 Application.onCreate 中注入 context。"
    }
}

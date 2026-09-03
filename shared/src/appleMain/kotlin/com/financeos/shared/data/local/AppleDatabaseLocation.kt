package com.financeos.shared.data.local

import androidx.room3.Room
import platform.Foundation.NSString
import platform.Foundation.stringByAppendingPathComponent

/**
 * 使用平台默认位置（或显式注入的路径）创建数据库。
 *
 * 位置语义与原 Swift `FinanceStore.DefaultStoreLocation` 一致：App Group
 * `group.com.financeos.ios` 可用时使用其中的 `FinanceOS/` 目录（与旧 `store.json` 同目录，
 * 便于小组件经 App Group 直接读取同一库文件）；否则回退到应用自身的 Application Support。
 */
fun createAppleFinanceOsDatabase(path: String? = null): FinanceOsDatabase {
    val resolved = path
        ?: (resolveAppleDatabaseDirectory() as NSString)
            .stringByAppendingPathComponent(databaseFileName())
    return buildFinanceOsDatabase(
        Room.databaseBuilder(
            name = resolved,
            factory = { FinanceOsDatabaseConstructor.initialize() },
        ),
    )
}

package com.financeos.shared.data.local

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSString
import platform.Foundation.stringByAppendingPathComponent

/** App Group 与旧 store.json 保持一致，小组件据此与 App 读写同一份数据。 */
private const val APP_GROUP_IDENTIFIER = "group.com.financeos.ios"
private const val DATABASE_DIRECTORY = "FinanceOS"
private const val DATABASE_FILE_NAME = "financeos.db"

/** 按 App Group 优先、Application Support 回退的顺序解析数据库目录并确保其存在。 */
@OptIn(ExperimentalForeignApi::class)
internal fun resolveAppleDatabaseDirectory(): String {
    val fileManager = NSFileManager.defaultManager
    val groupPath: String? = fileManager
        .containerURLForSecurityApplicationGroupIdentifier(APP_GROUP_IDENTIFIER)
        ?.path
    val basePath: String = groupPath
        ?: (NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String)
        ?: error("无法解析 Apple 平台数据库目录。")

    val directory = (basePath as NSString).stringByAppendingPathComponent(DATABASE_DIRECTORY)
    fileManager.createDirectoryAtPath(
        directory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return directory
}

internal fun databaseFileName(): String = DATABASE_FILE_NAME

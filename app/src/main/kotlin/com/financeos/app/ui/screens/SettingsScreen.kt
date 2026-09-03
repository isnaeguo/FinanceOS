package com.financeos.app.ui.screens
import com.financeos.app.ui.components.glassCardSecondaryText

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeos.app.ui.viewmodel.DataTransferEvent
import com.financeos.app.ui.viewmodel.DataTransferUiState
import com.financeos.app.ui.viewmodel.DataTransferViewModel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.graphics.Color
import com.financeos.app.ui.components.GlassCard

/** 连接系统文件选择器与数据导入导出状态。 */
@Composable
internal fun SettingsRoute(
    viewModel: DataTransferViewModel,
    onOpenLanShare: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(JSON_MIME_TYPE),
    ) { uri -> uri?.let(viewModel::exportJson) }
    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CSV_MIME_TYPE),
    ) { uri -> uri?.let(viewModel::exportCsv) }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(JSON_MIME_TYPE),
    ) { uri -> uri?.let(viewModel::createBackup) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importData) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::prepareRestore) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DataTransferEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    SettingsScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onOpenLanShare = onOpenLanShare,
        onExportJson = {
            exportJsonLauncher.launch("FinanceOS-data-${fileTimestamp()}.json")
        },
        onExportCsv = {
            exportCsvLauncher.launch("FinanceOS-transactions-${fileTimestamp()}.csv")
        },
        onImport = { importLauncher.launch(arrayOf(ALL_FILES_MIME_TYPE)) },
        onCreateBackup = {
            backupLauncher.launch("FinanceOS-backup-${fileTimestamp()}.json")
        },
        onChooseRestore = { restoreLauncher.launch(arrayOf(ALL_FILES_MIME_TYPE)) },
        onConfirmRestore = viewModel::confirmRestore,
        onDismissRestore = viewModel::dismissRestore,
    )
}

/** 设置与数据可携带页面。 */
@Composable
internal fun SettingsScreen(
    uiState: DataTransferUiState,
    snackbarHostState: SnackbarHostState,
    onOpenLanShare: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onImport: () -> Unit,
    onCreateBackup: () -> Unit,
    onChooseRestore: () -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissRestore: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { SettingsSectionTitle("导出数据") }
            item {
                SettingsAction(
                    title = "导出完整数据（JSON）",
                    description = "包含流水、分类和预算，格式公开且可再次导入",
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    enabled = !uiState.isBusy,
                    onClick = onExportJson,
                )
            }
            item {
                SettingsAction(
                    title = "导出流水（CSV）",
                    description = "适合使用表格软件查看和长期留存",
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    enabled = !uiState.isBusy,
                    onClick = onExportCsv,
                )
            }
            item { HorizontalDivider() }
            item { SettingsSectionTitle("导入数据") }
            item {
                SettingsAction(
                    title = "导入数据（JSON / CSV / XLSX）",
                    description = "自动识别文件格式；按 ID 合并，不会删除文件中未涉及的本机数据",
                    icon = Icons.Default.Add,
                    enabled = !uiState.isBusy,
                    onClick = onImport,
                )
            }
            item { HorizontalDivider() }
            item { SettingsSectionTitle("本地备份") }
            item {
                SettingsAction(
                    title = "创建备份文件",
                    description = "把当前全部数据保存到你选择的位置",
                    icon = Icons.Default.Star,
                    enabled = !uiState.isBusy,
                    onClick = onCreateBackup,
                )
            }
            item {
                SettingsAction(
                    title = "从备份恢复",
                    description = "确认后用备份完整替换本机数据",
                    icon = Icons.Default.Refresh,
                    enabled = !uiState.isBusy,
                    onClick = onChooseRestore,
                )
            }
            item { HorizontalDivider() }
            item {
                SettingsAction(
                    title = "局域网共享",
                    description = "与电脑/手机手动同步流水",
                    icon = Icons.Default.Share,
                    enabled = !uiState.isBusy,
                    onClick = onOpenLanShare,
                )
            }
            item { HorizontalDivider() }
            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("数据与隐私") },
                    supportingContent = { Text("FinanceOS 数据默认仅保存在本机；你可随时导出") },
                )
            }
            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("关于 FinanceOS") },
                    supportingContent = {
                        Text(
                            // 版本号取自构建配置，随 versionName 自动更新，避免硬编码过期。
                            text = "版本 ${appVersionName()} · isnaeguo",
                            color = glassCardSecondaryText(),
                        )
                    },
                )
            }
        }

        if (uiState.isBusy) {
            GlassCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("正在处理本地数据…")
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    uiState.pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissRestore,
            title = { Text("恢复本地备份？") },
            text = {
                Text(
                    "备份包含 ${pending.transactionCount} 笔流水、" +
                        "${pending.categoryCount} 个分类和 ${pending.budgetCount} 条预算。" +
                        "\n\n恢复会完整替换当前数据，此操作无法撤销。",
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmRestore, enabled = !uiState.isBusy) {
                    Text("确认恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRestore, enabled = !uiState.isBusy) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SettingsAction(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        colors = ListItemDefaults.colors(
            headlineColor = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (enabled) 1f else 0.38f,
            ),
            supportingColor = glassCardSecondaryText().copy(
                alpha = if (enabled) 1f else 0.38f,
            ),
            leadingIconColor = glassCardSecondaryText().copy(
                alpha = if (enabled) 1f else 0.38f,
            ),
        ),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
        color = glassCardSecondaryText(),
        style = MaterialTheme.typography.labelLarge,
    )
}

private fun fileTimestamp(): String = FILE_TIMESTAMP_FORMATTER.format(LocalDateTime.now())

private val FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private const val JSON_MIME_TYPE = "application/json"
private const val CSV_MIME_TYPE = "text/csv"

// 导入一律放行所有文件，由应用按文件内容识别格式（避免部分文档提供器因 MIME 过滤显示为空）。
private const val ALL_FILES_MIME_TYPE = "*/*"

/** 从 PackageManager 读取构建的 versionName，避免硬编码过期（返回 "未知" 兜底）。 */
@Composable
private fun appVersionName(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "未知"
    } catch (error: Exception) {
        "未知"
    }
}

package com.financeos.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeos.app.ui.viewmodel.LanShareEvent
import com.financeos.app.ui.viewmodel.LanShareUiState
import com.financeos.app.ui.viewmodel.LanShareViewModel

/** 连接局域网共享 ViewModel 与无数据依赖的内容。 */
@Composable
internal fun LanShareRoute(viewModel: LanShareViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LanShareEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    LanShareScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onPortTextChange = viewModel::onPortTextChange,
        onTargetHostChange = viewModel::onTargetHostChange,
        onClientPairingCodeChange = viewModel::onClientPairingCodeChange,
        onToggleServer = viewModel::toggleServer,
        onPullSnapshot = viewModel::pullSnapshot,
        onPushSnapshot = viewModel::pushSnapshot,
    )
}

/** 局域网手动共享页面：本机作为服务端，或作为客户端与对方同步。 */
@Composable
internal fun LanShareScreen(
    uiState: LanShareUiState,
    snackbarHostState: SnackbarHostState,
    onPortTextChange: (String) -> Unit,
    onTargetHostChange: (String) -> Unit,
    onClientPairingCodeChange: (String) -> Unit,
    onToggleServer: () -> Unit,
    onPullSnapshot: () -> Unit,
    onPushSnapshot: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "两台设备需接入同一局域网。服务端把本机数据经 HTTP 提供给对端；" +
                        "拉取与推送都按“合并”处理，不会删除任何一方的本机数据。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { SectionLabel("共享服务") }
            item {
                OutlinedTextField(
                    value = uiState.port,
                    onValueChange = onPortTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.serverRunning && !uiState.isBusy,
                    label = { Text("端口") },
                    supportingText = { Text("默认 45678，与电脑端一致") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            item {
                if (uiState.serverRunning) {
                    OutlinedButton(
                        onClick = onToggleServer,
                        enabled = !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("停止共享服务")
                    }
                } else {
                    Button(
                        onClick = onToggleServer,
                        enabled = !uiState.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("启动共享服务")
                    }
                }
            }
            item {
                StatusCard(
                    title = "本机共享地址",
                    body = if (uiState.serverRunning) {
                        uiState.serverStatusText
                    } else {
                        "服务未启动"
                    },
                )
            }
            if (uiState.serverRunning && uiState.pairingCode.isNotEmpty()) {
                item {
                    StatusCard(title = "本次配对码", body = "对方需输入该 10 位配对码才能同步；停止接收后即失效。")
                }
                item {
                    val clipboard = LocalClipboardManager.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = uiState.pairingCode,
                            modifier = Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleLarge,
                            letterSpacing = 4.sp,
                        )
                        Button(onClick = {
                            clipboard.setText(AnnotatedString(uiState.pairingCode))
                        }) {
                            Text("复制")
                        }
                    }
                }
            }
            item { HorizontalDivider() }
            item { SectionLabel("同步操作") }
            item {
                OutlinedTextField(
                    value = uiState.targetHost,
                    onValueChange = onTargetHostChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isBusy,
                    label = { Text("对方主机 IP") },
                    supportingText = { Text("例如 192.168.1.5，需对方共享服务已启动") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            item {
                OutlinedTextField(
                    value = uiState.clientPairingCode,
                    onValueChange = onClientPairingCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isBusy,
                    label = { Text("对方配对码") },
                    supportingText = { Text("请输入对方共享服务展示的 10 位配对码") },
                    singleLine = true,
                )
            }
            item {
                Button(
                    onClick = onPullSnapshot,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("拉取对方快照")
                }
            }
            item {
                OutlinedButton(
                    onClick = onPushSnapshot,
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("把我的快照推送给对方")
                }
            }
            item {
                StatusCard(title = "最近一次操作", body = uiState.lastResultText)
            }
        }

        if (uiState.isBusy) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("正在同步数据…")
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun StatusCard(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = body,
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

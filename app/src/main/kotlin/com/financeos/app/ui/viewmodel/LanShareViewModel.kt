package com.financeos.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financeos.app.data.FinanceDataBridge
import com.financeos.app.lanshare.DEFAULT_LAN_SHARE_PORT
import com.financeos.app.lanshare.LanShareClient
import com.financeos.app.lanshare.LanShareServer
import com.financeos.app.lanshare.LanShareServerState
import com.financeos.shared.domain.model.FinanceDataImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 局域网共享页面状态。 */
internal data class LanShareUiState(
    val port: String = DEFAULT_LAN_SHARE_PORT.toString(),
    val serverRunning: Boolean = false,
    val localAddresses: List<String> = emptyList(),
    val targetHost: String = "",
    val isBusy: Boolean = false,
    val serverStatusText: String = "服务未启动",
    val lastResultText: String = "尚未进行过同步操作",
)

internal sealed interface LanShareEvent {
    data class ShowMessage(val message: String) : LanShareEvent
}

/** 管理局域网共享服务启停与双向同步；错误通过 Snackbar 反馈。 */
internal class LanShareViewModel(
    private val bridge: FinanceDataBridge,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LanShareUiState())
    val uiState: StateFlow<LanShareUiState> = _uiState.asStateFlow()

    private val _events = Channel<LanShareEvent>(capacity = Channel.BUFFERED)
    val events: Flow<LanShareEvent> = _events.receiveAsFlow()

    private val server = LanShareServer(
        snapshotExporter = { bridge.exportSnapshotJson() },
        snapshotMerger = { content -> bridge.mergeImportJson(content) },
        onStateChange = ::onServerStateChanged,
        onSnapshotImported = ::onSnapshotImported,
    )

    fun onPortTextChange(raw: String) {
        if (_uiState.value.serverRunning) return
        val digits = raw.filter(Char::isDigit).take(MAX_PORT_DIGITS)
        _uiState.update { it.copy(port = digits) }
    }

    fun onTargetHostChange(raw: String) {
        _uiState.update { it.copy(targetHost = raw.trim()) }
    }

    fun toggleServer() {
        if (_uiState.value.isBusy) return
        if (server.isRunning) {
            server.stop()
            return
        }
        val port = parsedPort()
        if (port == null) {
            _events.trySend(LanShareEvent.ShowMessage("请输入 1-65535 之间的有效端口"))
            return
        }
        val started = server.start(port)
        if (!started) {
            _events.trySend(LanShareEvent.ShowMessage("服务启动失败，端口 $port 可能已被占用"))
        }
    }

    /** 从对方设备拉取完整快照并按合并语义写入本机。 */
    fun pullSnapshot() = runNetworkOperation("拉取对方快照") { host, port ->
        val json = LanShareClient.pullSnapshot(host, port)
        val result = bridge.mergeImportJson(json)
        "已从 $host 拉取快照并合并：${result.importCounts()}"
    }

    /** 把本机完整快照推送给对方设备，由对方完成合并导入。 */
    fun pushSnapshot() = runNetworkOperation("推送本机快照") { host, port ->
        val json = bridge.exportSnapshotJson()
        val result = LanShareClient.pushSnapshot(host, port, json)
        "已推送快照到 $host，对方合并：${result.importCounts()}"
    }

    private fun runNetworkOperation(
        actionLabel: String,
        operation: suspend (host: String, port: Int) -> String,
    ) {
        if (_uiState.value.isBusy) return
        val host = _uiState.value.targetHost
        val port = parsedPort()
        if (host.isBlank()) {
            _events.trySend(LanShareEvent.ShowMessage("请先填写对方主机 IP 或主机名"))
            return
        }
        if (port == null) {
            _events.trySend(LanShareEvent.ShowMessage("端口无效，请输入 1-65535"))
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val resultText = withContext(Dispatchers.IO) { operation(host, port) }
                _uiState.update { it.copy(lastResultText = resultText) }
                _events.send(LanShareEvent.ShowMessage(resultText))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message ?: "$actionLabel 失败，请稍后重试"
                _uiState.update { it.copy(lastResultText = message) }
                _events.send(LanShareEvent.ShowMessage(message))
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun onServerStateChanged(state: LanShareServerState) {
        _uiState.update { current ->
            val statusText = if (state.running) {
                val addresses = state.addresses
                if (addresses.isEmpty()) {
                    "服务已启动（端口 ${state.port}），但未找到局域网地址"
                } else {
                    "服务已启动，监听端口 ${state.port}。对方可访问：\n" +
                        addresses.joinToString("\n") { "http://$it:${state.port}" }
                }
            } else {
                "服务已停止"
            }
            current.copy(
                serverRunning = state.running,
                localAddresses = state.addresses,
                serverStatusText = statusText,
            )
        }
    }

    private fun onSnapshotImported(result: FinanceDataImportResult) {
        viewModelScope.launch {
            val text = "收到对方推送到本机，合并：${result.importCounts()}"
            _uiState.update { it.copy(lastResultText = text) }
            _events.send(LanShareEvent.ShowMessage(text))
        }
    }

    private fun parsedPort(): Int? {
        val port = _uiState.value.port.trim().toIntOrNull() ?: return null
        return port.takeIf { it in MIN_PORT..MAX_PORT }
    }

    override fun onCleared() {
        server.stop()
        super.onCleared()
    }
}

private fun FinanceDataImportResult.importCounts(): String =
    "$transactionCount 笔流水、$categoryCount 个分类、$budgetCount 条预算"

private const val MIN_PORT = 1
private const val MAX_PORT = 65535
private const val MAX_PORT_DIGITS = 5

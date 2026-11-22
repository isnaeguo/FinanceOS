package com.financeos.app.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeos.app.ui.components.FinanceCard
import com.financeos.app.ui.components.GeometryLoader
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkspaceAuditChat(
    model: WorkspaceProjection,
    busy: Boolean,
    viewModel: WorkspaceViewModel,
    onAiSettings: () -> Unit,
    onEditTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val history by viewModel.chat.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(WorkspaceChatMode.LEDGER) }
    var days by rememberSaveable { mutableStateOf(0) }
    var ledgerDraft by rememberSaveable { mutableStateOf("") }
    var generalDraft by rememberSaveable { mutableStateOf("") }
    val isLedger = mode == WorkspaceChatMode.LEDGER
    val draft = if (isLedger) ledgerDraft else generalDraft
    val turns = history.turns(mode)
    val listState = rememberLazyListState()
    val newestTurn = turns.lastOrNull()
    val limit = if (isLedger) 400 else 2_000
    val canSend = !busy && draft.trim().length in 1..limit
    val filters = listOf(0 to "本月", 30 to "近 30 天", 90 to "近 90 天")

    LaunchedEffect(mode, newestTurn?.id, newestTurn?.answer, newestTurn?.response, newestTurn?.explanation, newestTurn?.error) {
        if (turns.isNotEmpty()) listState.scrollToItem(turns.size)
    }

    fun send(ai: Boolean) {
        val question = draft.trim()
        if (isLedger) {
            viewModel.audit(question, ai, days)
            ledgerDraft = ""
        } else {
            viewModel.askGeneral(question)
            generalDraft = ""
        }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WorkspaceChatMode.entries.forEach { choice ->
                FilterChip(
                    selected = mode == choice,
                    onClick = { mode = choice },
                    enabled = !busy,
                    label = { Text(choice.label) },
                )
            }
            TextButton(onClick = onAiSettings, enabled = !busy) { Text("AI 接口") }
            if (turns.isNotEmpty()) TextButton(onClick = { viewModel.clearChat(mode) }, enabled = !busy) {
                Text("新对话")
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("chat-context") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isLedger) {
                        BookScope(model, busy, viewModel::selectBook)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            filters.forEach { (option, label) ->
                                FilterChip(
                                    selected = days == option,
                                    onClick = { days = option },
                                    enabled = !busy,
                                    label = { Text(label) },
                                )
                            }
                        }
                        ChatNote("可追问本月或上月。AI 解读仅发送汇总指标。")
                    } else {
                        ChatNote("发送时，所选服务会收到此模式中的近期对话。")
                    }
                }
            }
            items(turns, key = { it.id }) { turn ->
                TurnBubbles(turn, busy && turn.id == newestTurn?.id, isLedger, onEditTransaction)
            }
        }
        HorizontalDivider()
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        if (isLedger) ledgerDraft = it.take(limit) else generalDraft = it.take(limit)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (isLedger) "账本问题" else "消息") },
                    placeholder = { Text(if (isLedger) "例如：本月哪一类花得最多？" else "例如：如何规划每月预算？") },
                    enabled = !busy,
                    maxLines = 3,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { send(!isLedger) }, enabled = canSend) {
                        Text(if (isLedger) "本地查账" else "发送")
                    }
                    if (isLedger) {
                        OutlinedButton(onClick = { send(true) }, enabled = canSend) { Text("查账并解读") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TurnBubbles(
    turn: WorkspaceChatTurn,
    pending: Boolean,
    isLedger: Boolean,
    onEditTransaction: (String) -> Unit,
) {
    val answer = turn.answer
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.86f),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(turn.question, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (answer != null || turn.response != null || turn.error != null || pending) {
            FinanceCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (answer != null) LedgerAnswer(turn, onEditTransaction)
                    turn.response?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    turn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (pending && turn.error == null && turn.response == null && turn.explanation == null &&
                        (answer == null || turn.requestedAi)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GeometryLoader()
                            ChatNote(if (!isLedger || answer != null) "正在请求 AI" else "正在查账")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerAnswer(turn: WorkspaceChatTurn, onEditTransaction: (String) -> Unit) {
    val answer = checkNotNull(turn.answer)
    val highlighted = turn.explanation?.highlights.orEmpty()
    val highlightedIds = highlighted.mapTo(mutableSetOf()) { it.id }
    val facts = highlighted + answer.facts.filterNot { it.id in highlightedIds }
    val sourceByReference = remember(answer) { answer.sources.associateBy { it.reference } }
    var showSources by rememberSaveable(turn.id) { mutableStateOf(false) }
    Text(answer.title, style = MaterialTheme.typography.titleMedium)
    ChatNote("${localDate(answer.startEpochMillis)} 至 ${localDate(answer.endEpochMillis - 1)} · ${answer.matchedCount} 笔")
    if (highlighted.isNotEmpty()) ChatNote("AI 重点")
    facts.forEach { fact ->
        Text(fact.text, style = MaterialTheme.typography.bodyMedium)
        fact.sourceReferences.firstNotNullOfOrNull { sourceByReference[it] }?.let { source ->
            TextButton(onClick = { onEditTransaction(source.transaction.id) }) {
                Text("查看来源 ${source.reference}")
            }
        }
    }
    turn.explanation?.followUp?.takeIf { it.isNotBlank() }?.let { ChatNote(it) }
    if (answer.sources.isNotEmpty()) {
        TextButton(onClick = { showSources = !showSources }) {
            Text(if (showSources) "收起来源" else "全部来源（${answer.sources.size}）")
        }
        if (showSources) {
            answer.sources.forEach { source ->
                TextButton(onClick = { onEditTransaction(source.transaction.id) }) {
                    Text("${source.reference} · ${source.transaction.workspaceLabel}")
                }
            }
            if (answer.omittedSourceCount > 0) ChatNote("另有 ${answer.omittedSourceCount} 笔未列出。")
        }
    }
}

@Composable
private fun ChatNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

private fun localDate(epochMillis: Long) =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

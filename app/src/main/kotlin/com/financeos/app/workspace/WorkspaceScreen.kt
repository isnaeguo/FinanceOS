package com.financeos.app.workspace

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeos.app.FinanceEntry
import com.financeos.app.ui.components.EmptyState
import com.financeos.app.ui.components.FinanceCard
import com.financeos.app.ui.components.LoadingState
import com.financeos.app.ui.components.WorkspaceGlyph
import com.financeos.app.ui.components.WorkspaceSymbol
import com.financeos.app.ui.viewmodel.formatMoney
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.workspace.WorkspaceInboxKind
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class WorkspaceTab(val title: String, val symbol: WorkspaceSymbol) {
    INBOX("收件箱", WorkspaceSymbol.INBOX),
    ACCOUNTS("账户对账", WorkspaceSymbol.ACCOUNTS),
    FORECAST("现金流", WorkspaceSymbol.FORECAST),
    BOOKS("项目资产", WorkspaceSymbol.BOOKS),
    AUDIT("AI 助手", WorkspaceSymbol.CHAT),
}

@Composable
internal fun WorkspaceRoute(
    viewModel: WorkspaceViewModel,
    receiptEntry: FinanceEntry.ReceiptImage?,
    onEntryHandled: (FinanceEntry) -> Unit,
    onEditTransaction: (String) -> Unit,
    onAiSettings: () -> Unit,
    startInChat: Boolean = false,
) {
    val projection by viewModel.projection.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    var tab by rememberSaveable {
        mutableStateOf(if (startInChat) WorkspaceTab.AUDIT else WorkspaceTab.INBOX)
    }
    var editor by rememberSaveable(stateSaver = editorSaver) { mutableStateOf<WorkspaceEditor?>(null) }
    var receiptReference by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var showRules by rememberSaveable { mutableStateOf(false) }
    val screenState = rememberSaveableStateHolder()
    val showingChat = tab == WorkspaceTab.AUDIT && !showRules
    val feedback = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val current = projection

    fun clearCapture() {
        val file = capturePath?.let(::File) ?: return
        capturePath = null
        scope.launch(Dispatchers.IO) { file.delete() }
    }

    fun open(request: WorkspaceEditor) {
        viewModel.dismissMessage()
        editor = request
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { open(WorkspaceEditor(WorkspaceEditorKind.INBOX, attachment = it)) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = capturePath
        if (saved && path != null) {
            open(WorkspaceEditor(WorkspaceEditorKind.INBOX, attachment = viewModel.cameraUri(File(path))))
        } else {
            clearCapture()
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportActiveBook)
    }
    val exportConfiguration = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportConfiguration)
    }

    LaunchedEffect(receiptEntry, current != null) {
        if (receiptEntry != null && current != null) {
            tab = WorkspaceTab.INBOX
            open(WorkspaceEditor(WorkspaceEditorKind.INBOX, attachment = receiptEntry.uri))
            onEntryHandled(receiptEntry)
        }
    }
    LaunchedEffect(operation.message, operation.error, operation.busy) {
        if (operation.busy) return@LaunchedEffect
        operation.message?.let { message ->
            editor = null
            clearCapture()
            viewModel.dismissMessage()
            scope.launch { feedback.showSnackbar(message) }
        }
        if (editor == null && current != null) {
            operation.error?.let { error ->
                viewModel.dismissMessage()
                scope.launch { feedback.showSnackbar(error) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (current == null) {
            if (operation.error == null) {
                LoadingState(label = "正在读取财务工作台", modifier = Modifier.align(Alignment.Center))
            } else {
                EmptyState(
                    title = "工作台暂时无法打开",
                    description = operation.error.orEmpty(),
                    actionLabel = "重试",
                    onAction = viewModel::reload,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(WorkspaceTab.entries, key = { it.name }) { section ->
                        val active = tab == section
                        val emphasis by animateColorAsState(
                            if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                            animationSpec = tween(if (ValueAnimator.areAnimatorsEnabled()) 120 else 0),
                            label = "工作台导航",
                        )
                        Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                            TextButton(onClick = { tab = section }, modifier = Modifier.semantics { selected = active }) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    WorkspaceGlyph(section.symbol)
                                    Text(section.title, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(2.dp).background(emphasis))
                        }
                    }
                }
                if (tab == WorkspaceTab.AUDIT) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showRules, onClick = { showRules = false }, label = { Text("对话") })
                        FilterChip(selected = showRules, onClick = { showRules = true }, label = { Text("规则") })
                    }
                }
                if (showingChat) {
                    screenState.SaveableStateProvider("workspace-audit") {
                        WorkspaceAuditChat(
                            model = current,
                            busy = operation.busy,
                            viewModel = viewModel,
                            onAiSettings = onAiSettings,
                            onEditTransaction = onEditTransaction,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    if (operation.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (tab) {
                            WorkspaceTab.INBOX -> inbox(
                                current, operation.busy, showCompleted, { showCompleted = !showCompleted },
                                onScope = viewModel::selectBook,
                                onAdd = { open(WorkspaceEditor(WorkspaceEditorKind.INBOX)) },
                                onGallery = { gallery.launch("image/*") },
                                onCamera = {
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { viewModel.createCameraCapture() } }
                                            .onSuccess { file ->
                                                capturePath = file.absolutePath
                                                runCatching { camera.launch(viewModel.cameraUri(file)) }.onFailure {
                                                    clearCapture()
                                                    feedback.showSnackbar("无法打开相机，可改选图片。")
                                                }
                                            }
                                            .onFailure { feedback.showSnackbar("无法创建票据文件。") }
                                    }
                                },
                                onReceipt = { receiptReference = it },
                                onEdit = { open(WorkspaceEditor(WorkspaceEditorKind.INBOX, existingId = it)) },
                                onComplete = viewModel::completeItem,
                                onReopen = viewModel::reopenItem,
                                onRemove = { id -> pendingRemoval = "移除这项记录？" to { viewModel.removeItem(id) } },
                                onEditTransaction = onEditTransaction,
                            )
                            WorkspaceTab.ACCOUNTS -> accounts(
                                current, operation.busy, ::open,
                                onUnlink = { id -> pendingRemoval = "取消这组流水关联？" to { viewModel.removeLink(id) } },
                                onEditTransaction = onEditTransaction,
                            )
                            WorkspaceTab.FORECAST -> forecast(
                                current, operation.busy, viewModel::forecastDays, viewModel::selectScenario, ::open,
                                onScope = viewModel::selectBook,
                                onRemovePlan = { id -> pendingRemoval = "移除这项现金流计划？" to { viewModel.removePlan(id) } },
                            )
                            WorkspaceTab.BOOKS -> books(
                                current, operation.busy, ::open,
                                onScope = viewModel::selectBook,
                                onExport = { export.launch("FinanceOS-project-${LocalDate.now()}.json") },
                                onExportConfiguration = { exportConfiguration.launch("FinanceOS-Workspace.json") },
                                netWorth = { viewModel.netWorth(current) },
                                onEditTransaction = onEditTransaction,
                            )
                            WorkspaceTab.AUDIT -> auditRules(
                                current, operation, viewModel, ::open,
                                onScope = viewModel::selectBook,
                                onRemoveRule = { id -> pendingRemoval = "移除这条规则？" to { viewModel.removeRule(id) } },
                                onEditTransaction = onEditTransaction,
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(feedback, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (current != null) editor?.let { request ->
        WorkspaceEditorDialog(request, current, operation, viewModel) {
            editor = null
            clearCapture()
            viewModel.dismissMessage()
        }
    }
    pendingRemoval?.let { (title, action) ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        action()
                    },
                ) { Text("确认", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("取消") } },
        )
    }
    receiptReference?.let { reference ->
        ReceiptDialog(reference, viewModel) { receiptReference = null }
    }
}

private fun LazyListScope.inbox(
    model: WorkspaceProjection,
    busy: Boolean,
    showCompleted: Boolean,
    onToggleCompleted: () -> Unit,
    onScope: (String?) -> Unit,
    onAdd: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onReceipt: (String) -> Unit,
    onEdit: (String) -> Unit,
    onComplete: (String) -> Unit,
    onReopen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEditTransaction: (String) -> Unit,
) {
    item("inbox-header") {
        WorkspacePanel {
            WorkspaceHeading("待处理", if (showCompleted) "已完成记录" else "${model.inbox.size} 项")
            BookScope(model, busy, onScope)
            WorkspaceActions(
                "添加事项" to onAdd,
                "选票据" to onGallery,
                "拍照" to onCamera,
                enabled = !busy,
            )
            FilterChip(
                selected = showCompleted,
                onClick = onToggleCompleted,
                label = { Text(if (showCompleted) "查看待处理" else "查看已完成") },
            )
        }
    }
    val items = if (showCompleted) {
        model.state.automation.items.filter { it.completed && it.bookId == model.selection.bookId }
    } else {
        model.inbox
    }
    if (items.isEmpty()) item("inbox-empty") { WorkspaceNote("没有${if (showCompleted) "已完成" else "待处理"}事项。") }
    items(items, key = { "inbox-${it.id}" }) { entry ->
        WorkspacePanel {
            WorkspaceHeading(entry.title, entry.kind.label)
            if (entry.note.isNotBlank()) WorkspaceNote(entry.note)
            val due = entry.dueAtEpochMillis.takeIf { it > 0 }?.let(::dateOfMillis)
            val amount = entry.amountMinor.takeIf { it > 0 }?.let(::formatMoney)
            listOfNotNull(due?.let { "到期 $it" }, amount).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                WorkspaceNote(it)
            }
            entry.attachmentReference?.let { reference ->
                TextButton(onClick = { onReceipt(reference) }) { Text(entry.attachmentName ?: "查看票据") }
            }
            entry.transactionIds.forEachIndexed { index, id ->
                TextButton(onClick = { onEditTransaction(id) }) {
                    Text(if (entry.transactionIds.size == 1) "查看关联流水" else "查看关联流水 ${index + 1}")
                }
            }
            val autoSignal = entry.kind in listOf(
                WorkspaceInboxKind.UNCATEGORIZED, WorkspaceInboxKind.DUPLICATE, WorkspaceInboxKind.PRICE_INCREASE,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!autoSignal) {
                    Button(onClick = { if (entry.completed) onReopen(entry.id) else onComplete(entry.id) }, enabled = !busy) {
                        Text(if (entry.completed) "重新打开" else "完成")
                    }
                    TextButton(onClick = { onEdit(entry.id) }, enabled = !busy) { Text("编辑") }
                }
                TextButton(onClick = { onRemove(entry.id) }, enabled = !busy) {
                    Text(if (autoSignal) "忽略" else "移除")
                }
            }
        }
    }
}

private fun LazyListScope.accounts(
    model: WorkspaceProjection,
    busy: Boolean,
    onEditor: (WorkspaceEditor) -> Unit,
    onUnlink: (String) -> Unit,
    onEditTransaction: (String) -> Unit,
) {
    item("accounts-header") {
        WorkspacePanel {
            WorkspaceHeading("账户与对账", "${model.summary.unassignedCount} 笔未分配")
            if (model.summary.unresolvedLinkCount > 0) {
                WorkspaceNote("${model.summary.unresolvedLinkCount} 组关联需要复查")
            }
            WorkspaceActions(
                "新建账户" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.ACCOUNT)) },
                "关联流水" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.ASSIGNMENT)) },
                "匹配流水" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.LINK)) },
                enabled = !busy,
            )
        }
    }
    items(model.summary.accountBalances, key = { "account-${it.accountId}" }) { account ->
        WorkspacePanel {
            WorkspaceHeading(account.name, formatCurrency(account.balanceMinor, account.currency))
            WorkspaceNote("${account.transactionCount} 笔流水" +
                if (account.unvaluedCount > 0) " · ${account.unvaluedCount} 笔缺少原币金额" else "")
            WorkspaceActions(
                "编辑账户" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.ACCOUNT, account.accountId)) },
                "记录账单余额" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.CHECKPOINT, account.accountId)) },
                enabled = !busy,
            )
        }
    }
    if (model.summary.reconciliations.isNotEmpty()) item("reconcile-label") { WorkspaceHeading("对账记录") }
    items(model.summary.reconciliations, key = { "reconcile-${it.checkpointId}" }) { check ->
        val account = model.state.accounts.firstOrNull { it.id == check.accountId }
        val currency = account?.currency ?: "CNY"
        WorkspacePanel {
            WorkspaceHeading(
                account?.name ?: "账户",
                if (check.unvaluedCount > 0) "待补原币金额"
                else if (check.differenceMinor == 0L) "账实相符"
                else "差额 ${formatCurrency(check.differenceMinor, currency)}",
            )
            WorkspaceNote(dateOfMillis(check.asOfEpochMillis).toString())
            WorkspaceNote(
                "实际 ${formatCurrency(check.statementBalanceMinor, currency)} · 账本 ${formatCurrency(check.ledgerBalanceMinor, currency)}",
            )
        }
    }
    if (model.state.links.isNotEmpty()) item("links-label") { WorkspaceHeading("流水匹配") }
    items(model.state.links, key = { "link-${it.sourceId}" }) { link ->
        WorkspacePanel {
            WorkspaceHeading(link.kind.label)
            WorkspaceActions(
                "查看第一笔" to { onEditTransaction(link.sourceId) },
                "查看关联笔" to { onEditTransaction(link.targetId) },
                "取消匹配" to { onUnlink(link.sourceId) },
                enabled = !busy,
            )
        }
    }
}

private fun LazyListScope.forecast(
    model: WorkspaceProjection,
    busy: Boolean,
    onDays: (Int) -> Unit,
    onScenario: (String?) -> Unit,
    onEditor: (WorkspaceEditor) -> Unit,
    onScope: (String?) -> Unit,
    onRemovePlan: (String) -> Unit,
) {
    val plans = model.state.plans.filter { model.selection.bookId == null || it.bookId == model.selection.bookId }
    item("forecast-header") {
        WorkspacePanel {
            WorkspaceHeading("预计期末余额", formatMoney(model.forecast.closingBalanceMinor))
            BookScope(model, busy, onScope)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 90).forEach { days ->
                    FilterChip(selected = model.selection.forecastDays == days, onClick = { onDays(days) }, label = { Text("${days} 天") })
                }
            }
            WorkspaceChoice(
                "试算情景",
                model.selection.scenario.orEmpty(),
                listOf("" to "基准") + plans.mapNotNull { it.scenario }.distinct().map { it to it },
                optional = false,
                enabled = !busy,
            ) { onScenario(it.ifBlank { null }) }
            WorkspaceNote("期初 ${formatMoney(model.forecast.openingBalanceMinor)} · 最低 ${formatMoney(model.forecast.lowBalanceMinor)}")
            WorkspaceNote("预计收入 ${formatMoney(model.forecast.incomeMinor)} · 支出 ${formatMoney(model.forecast.expenseMinor)}")
            val values = remember(model.forecast) { model.forecast.points.map { it.balanceMinor } }
            BalanceLine(values)
            model.forecast.points.minByOrNull { it.balanceMinor }?.takeIf { it.balanceMinor < 0 }?.let {
                Text(
                    "${LocalDate.ofEpochDay(it.epochDay)} 预计余额为负。",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = { onEditor(WorkspaceEditor(WorkspaceEditorKind.PLAN)) }, enabled = !busy) {
                Text("添加计划")
            }
        }
    }
    val dated = model.forecast.points.filter { it.incomeMinor > 0 || it.expenseMinor > 0 }
    if (dated.isNotEmpty()) item("forecast-dates") { WorkspaceHeading("预计变动") }
    items(dated, key = { "day-${it.epochDay}" }) { day ->
        WorkspacePanel {
            WorkspaceHeading(LocalDate.ofEpochDay(day.epochDay).toString(), formatMoney(day.balanceMinor))
            WorkspaceNote("收入 ${formatMoney(day.incomeMinor)} · 支出 ${formatMoney(day.expenseMinor)}")
        }
    }
    if (plans.isNotEmpty()) item("plan-heading") { WorkspaceHeading("现金流计划") }
    items(plans, key = { "plan-${it.id}" }) { plan ->
        WorkspacePanel {
            val sign = if (plan.type == TransactionType.EXPENSE) "−" else "+"
            WorkspaceHeading(plan.name, "$sign${formatMoney(plan.amountMinor)}")
            WorkspaceNote("${LocalDate.ofEpochDay(plan.firstEpochDay)} · ${plan.repeat.label} · ${plan.scenario ?: "基准"}")
            WorkspaceActions(
                "编辑" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.PLAN, plan.id)) },
                "移除" to { onRemovePlan(plan.id) },
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun BalanceLine(points: List<Long>) {
    if (points.isEmpty()) return
    val color = MaterialTheme.colorScheme.primary
    val border = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxWidth().height(92.dp).drawWithCache {
        val min = points.min().toDouble()
        val range = (points.max().toDouble() - min).coerceAtLeast(1.0)
        val inset = 5.dp.toPx()
        val bodyHeight = (size.height - 2 * inset).coerceAtLeast(1f)
        val path = Path().apply {
            points.forEachIndexed { index, point ->
                val x = size.width * index / (points.size - 1).coerceAtLeast(1)
                val y = inset + bodyHeight * (1f - ((point.toDouble() - min) / range).toFloat())
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        onDrawBehind {
            drawLine(border, androidx.compose.ui.geometry.Offset(0f, size.height - inset), androidx.compose.ui.geometry.Offset(size.width, size.height - inset))
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
    })
}

private fun LazyListScope.books(
    model: WorkspaceProjection,
    busy: Boolean,
    onEditor: (WorkspaceEditor) -> Unit,
    onScope: (String?) -> Unit,
    onExport: () -> Unit,
    onExportConfiguration: () -> Unit,
    netWorth: suspend () -> List<Long>,
    onEditTransaction: (String) -> Unit,
) {
    item("books-header") {
        WorkspacePanel {
            WorkspaceHeading("项目账本")
            BookScope(model, busy, onScope)
            WorkspaceActions(
                "新建项目" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.BOOK)) },
                "分配流水" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.ASSIGNMENT)) },
                enabled = !busy,
            )
            if (model.selection.bookId != null) TextButton(onClick = onExport, enabled = !busy) { Text("导出此项目") }
            TextButton(onClick = onExportConfiguration, enabled = !busy) { Text("导出工作台配置") }
        }
    }
    items(model.summary.bookBalances, key = { "book-${it.bookId}" }) { book ->
        WorkspacePanel {
            WorkspaceHeading(book.name, book.currency)
            WorkspaceNote("收入 ${formatCurrency(book.incomeMinor, book.currency)} · 支出 ${formatCurrency(book.expenseMinor, book.currency)}")
            if (book.unvaluedCount > 0) WorkspaceNote("${book.unvaluedCount} 笔缺少原币金额")
            WorkspaceActions(
                "查看项目" to { onScope(book.bookId) },
                "编辑项目" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.BOOK, book.bookId)) },
                enabled = !busy,
            )
        }
    }
    if (model.selection.bookId != null) {
        item("scoped-title") { WorkspaceHeading("项目流水", "${model.scopedTransactions.size} 笔") }
        items(model.scopedTransactions, key = { "bookrow-${it.id}" }) { transaction ->
            WorkspacePanel {
                WorkspaceNote(transaction.workspaceLabel)
                WorkspaceActions(
                    "查看流水" to { onEditTransaction(transaction.id) },
                    "调整分配" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.ASSIGNMENT, transaction.id)) },
                    enabled = !busy,
                )
            }
        }
    } else if (model.summary.accountBalances.isNotEmpty()) {
        item("asset-label") {
            WorkspacePanel {
                WorkspaceHeading("资产与负债")
                val balances = model.summary.accountBalances.groupBy { it.currency }
                balances.forEach { (currency, accounts) ->
                    val assets = accounts.filter { it.balanceMinor >= 0 }.sumBalances()
                    val debts = accounts.filter { it.balanceMinor < 0 }.sumBalances().negate()
                    WorkspaceNote("$currency 资产 $assets · 负债 $debts · 净额 ${assets.subtract(debts)}")
                }
                if ("CNY" in balances) {
                    val values by produceState<List<Long>?>(null, model.transactions, model.state.accounts, model.state.assignments, model.state.links) {
                        value = try { netWorth() } catch (_: IllegalArgumentException) { emptyList() }
                    }
                    WorkspaceNote("12 周人民币净资产")
                    when {
                        values == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        values!!.isEmpty() -> WorkspaceNote("历史净资产金额超出可显示范围。")
                        else -> BalanceLine(values.orEmpty())
                    }
                }
            }
        }
        items(model.summary.accountBalances, key = { "asset-${it.accountId}" }) { account ->
            WorkspacePanel {
                WorkspaceHeading(account.name, formatCurrency(account.balanceMinor, account.currency))
                WorkspaceNote(if (account.balanceMinor < 0) "负债" else "资产")
            }
        }
    }
}

private fun List<com.financeos.shared.workspace.WorkspaceAccountBalance>.sumBalances() = fold(BigDecimal.ZERO.setScale(2)) { total, account ->
    total.add(BigDecimal.valueOf(account.balanceMinor, 2))
}

private fun LazyListScope.auditRules(
    model: WorkspaceProjection,
    operation: WorkspaceOperation,
    viewModel: WorkspaceViewModel,
    onEditor: (WorkspaceEditor) -> Unit,
    onScope: (String?) -> Unit,
    onRemoveRule: (String) -> Unit,
    onEditTransaction: (String) -> Unit,
) {
    item("rule-header") {
        WorkspacePanel {
            WorkspaceHeading("分类规则", "${model.ruleSuggestions.size} 笔待确认")
            BookScope(model, operation.busy, onScope)
            Button(onClick = { onEditor(WorkspaceEditor(WorkspaceEditorKind.RULE)) }, enabled = !operation.busy) {
                Text("新建规则")
            }
        }
    }
    items(model.state.automation.rules, key = { "rule-${it.id}" }) { rule ->
        WorkspacePanel {
            WorkspaceHeading(rule.name, if (rule.enabled) "启用" else "停用")
            val category = model.categories.firstOrNull { it.id == rule.categoryId }?.name ?: "原分类已移除"
            WorkspaceNote("备注包含“${rule.noteContains}” · $category")
            WorkspaceActions(
                "编辑" to { onEditor(WorkspaceEditor(WorkspaceEditorKind.RULE, rule.id)) },
                "移除" to { onRemoveRule(rule.id) },
                enabled = !operation.busy,
            )
        }
    }
    items(model.ruleSuggestions, key = { "rule-row-${it.transaction.id}" }) { suggestion ->
        val category = model.categories.firstOrNull { it.id == suggestion.categoryId }?.name ?: "分类已移除"
        WorkspacePanel {
            WorkspaceHeading(category)
            WorkspaceNote(suggestion.transaction.workspaceLabel)
            WorkspaceActions(
                "确认分类" to { viewModel.applyRule(suggestion) },
                "查看流水" to { onEditTransaction(suggestion.transaction.id) },
                enabled = !operation.busy,
            )
        }
    }
}

@Composable
internal fun BookScope(model: WorkspaceProjection, busy: Boolean, onScope: (String?) -> Unit) {
    WorkspaceChoice(
        label = "范围",
        selected = model.selection.bookId.orEmpty(),
        options = listOf("" to "全部流水") + model.state.books.map { it.id to it.name },
        optional = false,
        enabled = !busy,
    ) { onScope(it.ifBlank { null }) }
}

@Composable
private fun ReceiptDialog(reference: String, viewModel: WorkspaceViewModel, onDismiss: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, reference) {
        value = runCatching { viewModel.loadReceipt(reference) }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("票据") },
        text = {
            val image = bitmap
            if (image == null) {
                Text("正在读取票据或图片不可用。")
            } else {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "已归档的票据",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun WorkspacePanel(content: @Composable () -> Unit) {
    FinanceCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun WorkspaceHeading(title: String, secondary: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        secondary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun WorkspaceNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceActions(vararg actions: Pair<String, () -> Unit>, enabled: Boolean) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEachIndexed { index, (title, action) ->
            if (index == 0) {
                Button(onClick = action, enabled = enabled) { Text(title) }
            } else {
                OutlinedButton(onClick = action, enabled = enabled) { Text(title) }
            }
        }
    }
}

private fun formatCurrency(amount: Long, currency: String): String =
    if (currency == "CNY") formatMoney(amount) else "$currency ${BigDecimal.valueOf(amount, 2).toPlainString()}"

private fun dateOfMillis(epochMillis: Long): LocalDate =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private val editorSaver = listSaver<WorkspaceEditor?, String>(
    save = { editor ->
        if (editor == null) emptyList()
        else listOf(editor.kind.name, editor.existingId.orEmpty(), editor.attachment?.toString().orEmpty())
    },
    restore = { values ->
        values.firstOrNull()?.let { kind ->
            WorkspaceEditor(
                WorkspaceEditorKind.valueOf(kind),
                values[1].ifBlank { null },
                values[2].ifBlank { null }?.let(Uri::parse),
            )
        }
    },
)

package com.financeos.app.workspace

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeos.app.ui.viewmodel.amountInputFromMinorUnits
import com.financeos.app.ui.viewmodel.parseAmountInMinorUnits
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.workspace.WorkspaceAccount
import com.financeos.shared.workspace.WorkspaceAssignment
import com.financeos.shared.workspace.WorkspaceBook
import com.financeos.shared.workspace.WorkspaceCheckpoint
import com.financeos.shared.workspace.WorkspaceInboxItem
import com.financeos.shared.workspace.WorkspaceInboxKind
import com.financeos.shared.workspace.WorkspaceLink
import com.financeos.shared.workspace.WorkspaceLinkKind
import com.financeos.shared.workspace.WorkspacePlan
import com.financeos.shared.workspace.WorkspaceRepeat
import com.financeos.shared.workspace.WorkspaceRule
import com.financeos.shared.workspace.WorkspaceSplit
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class WorkspaceEditorKind(val title: String) {
    ACCOUNT("账户"),
    CHECKPOINT("对账"),
    BOOK("项目账本"),
    ASSIGNMENT("关联流水"),
    LINK("匹配流水"),
    PLAN("现金流计划"),
    INBOX("收件箱项目"),
    RULE("分类规则"),
}

internal data class WorkspaceEditor(
    val kind: WorkspaceEditorKind,
    val existingId: String? = null,
    val attachment: Uri? = null,
)

@Composable
internal fun WorkspaceEditorDialog(
    editor: WorkspaceEditor,
    projection: WorkspaceProjection,
    operation: WorkspaceOperation,
    viewModel: WorkspaceViewModel,
    onDismiss: () -> Unit,
) {
    val state = projection.state
    val previousItem = state.automation.items.firstOrNull { it.id == editor.existingId }
    val values = remember(editor) {
        mutableStateMapOf(
            "currency" to "CNY",
            "date" to LocalDate.now().toString(),
            "amount" to "0",
            "repeat" to WorkspaceRepeat.ONCE.name,
            "direction" to TransactionType.EXPENSE.name,
            "count" to "1",
            "kind" to if (editor.attachment != null) WorkspaceInboxKind.RECEIPT.name else WorkspaceInboxKind.REFUND.name,
            "link" to WorkspaceLinkKind.DUPLICATE.name,
            "book" to projection.selection.bookId.orEmpty(),
        ).apply {
            val id = editor.existingId
            when (editor.kind) {
                WorkspaceEditorKind.ACCOUNT -> state.accounts.find { it.id == id }?.let {
                    putAll(mapOf("title" to it.name, "currency" to it.currency, "amount" to inputAmount(it.openingBalanceMinor)))
                }
                WorkspaceEditorKind.BOOK -> state.books.find { it.id == id }?.let {
                    putAll(mapOf("title" to it.name, "currency" to it.currency))
                }
                WorkspaceEditorKind.PLAN -> state.plans.find { it.id == id }?.let {
                    putAll(
                        mapOf(
                            "title" to it.name,
                            "amount" to inputAmount(it.amountMinor),
                            "direction" to it.type.name,
                            "date" to LocalDate.ofEpochDay(it.firstEpochDay).toString(),
                            "repeat" to it.repeat.name,
                            "count" to it.occurrences.toString(),
                            "account" to it.accountId.orEmpty(),
                            "book" to it.bookId.orEmpty(),
                            "scenario" to it.scenario.orEmpty(),
                        ),
                    )
                }
                WorkspaceEditorKind.ASSIGNMENT -> state.assignments.find { it.transactionId == id }?.let {
                    putAll(
                        mapOf(
                            "transaction" to it.transactionId,
                            "account" to it.accountId.orEmpty(),
                            "book" to it.bookId.orEmpty(),
                            "originalCurrency" to it.originalCurrency.orEmpty(),
                            "originalAmount" to it.originalAmountMinor?.let(::inputAmount).orEmpty(),
                        ),
                    )
                    it.splits.forEachIndexed { index, split ->
                        put("splitBook$index", split.bookId)
                        put("splitAmount$index", inputAmount(split.amountMinor))
                    }
                } ?: id?.let { put("transaction", it) }
                WorkspaceEditorKind.LINK -> id?.let { put("transaction", it) }
                WorkspaceEditorKind.CHECKPOINT -> id?.let { put("account", it) }
                WorkspaceEditorKind.RULE -> state.automation.rules.find { it.id == id }?.let {
                    putAll(
                        mapOf(
                            "title" to it.name,
                            "contains" to it.noteContains,
                            "category" to it.categoryId,
                            "book" to it.bookId.orEmpty(),
                            "disabled" to (!it.enabled).toString(),
                        ),
                    )
                }
                WorkspaceEditorKind.INBOX -> {
                    put("date", "")
                    put("amount", "")
                    previousItem?.let {
                        put("title", it.title)
                        put("note", it.note)
                        put("kind", it.kind.name)
                        put("book", it.bookId.orEmpty())
                        put("transaction", it.transactionIds.firstOrNull().orEmpty())
                        if (it.dueAtEpochMillis > 0) put("date", Instant.ofEpochMilli(it.dueAtEpochMillis)
                            .atZone(ZoneId.systemDefault()).toLocalDate().toString())
                        if (it.amountMinor > 0) put("amount", inputAmount(it.amountMinor))
                    }
                }
            }
        }
    }
    var localError by remember(editor) { mutableStateOf<String?>(null) }
    var readingReceipt by remember(editor) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var splitCount by remember(editor) {
        mutableStateOf(state.assignments.firstOrNull { it.transactionId == editor.existingId }?.splits?.size ?: 0)
    }
    fun text(key: String) = values[key].orEmpty().trim()
    fun optional(key: String) = text(key).ifBlank { null }
    fun title() = text("title").ifBlank { throw IllegalArgumentException("请填写名称。") }
    fun money(key: String, allowZero: Boolean = false): Long =
        parseAmountInMinorUnits(text(key), allowZero) ?: throw IllegalArgumentException("请输入有效金额，最多两位小数。")
    fun signedMoney(): Long = parseSignedAmount(text("amount"))
    fun date(key: String): LocalDate = runCatching { LocalDate.parse(text(key)) }
        .getOrElse { throw IllegalArgumentException("请输入 YYYY-MM-DD 格式的日期。") }
    fun required(key: String, message: String) = optional(key) ?: throw IllegalArgumentException(message)
    val accountChoices = state.accounts.map { it.id to it.name }
    val bookChoices = state.books.map { it.id to it.name }

    @Composable
    fun input(key: String, label: String, keyboard: KeyboardType = KeyboardType.Text, lines: Int = 1) {
        OutlinedTextField(
            value = values[key].orEmpty(),
            onValueChange = {
                values[key] = it
                localError = null
            },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = lines == 1,
            maxLines = lines,
            enabled = !operation.busy && (key != "note" || !readingReceipt),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        )
    }

    @Composable
    fun choice(key: String, label: String, options: List<Pair<String, String>>, optional: Boolean = true) {
        WorkspaceChoice(label, values[key].orEmpty(), options, optional, !operation.busy) { values[key] = it }
    }

    @Composable
    fun transaction(key: String, label: String, optional: Boolean = false) {
        WorkspaceTransactionChoice(projection.transactions, values[key].orEmpty(), label, optional, !operation.busy) {
            values[key] = it
        }
    }

    AlertDialog(
        onDismissRequest = { if (!operation.busy) onDismiss() },
        title = { Text(editor.kind.title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (editor.kind) {
                    WorkspaceEditorKind.ACCOUNT -> {
                        input("title", "账户名称")
                        if (editor.existingId == null && projection.unmanagedAccountAliases.isNotEmpty()) {
                            choice(
                                "alias",
                                "匹配已有账单账户",
                                projection.unmanagedAccountAliases.map { it to it },
                            )
                        }
                        input("currency", "币种代码")
                        input("amount", "期初余额（负数为负债）")
                    }
                    WorkspaceEditorKind.CHECKPOINT -> {
                        choice("account", "账户", accountChoices, optional = false)
                        input("date", "账单日期")
                        input("amount", "实际账单余额")
                    }
                    WorkspaceEditorKind.BOOK -> {
                        input("title", "项目名称")
                        input("currency", "币种代码")
                    }
                    WorkspaceEditorKind.ASSIGNMENT -> {
                        transaction("transaction", "流水")
                        choice("account", "账户", accountChoices)
                        if (splitCount == 0) choice("book", "项目", bookChoices)
                        input("originalCurrency", "原币币种（可选）")
                        input("originalAmount", "原币金额（可选）", KeyboardType.Decimal)
                        repeat(splitCount) { index ->
                            choice("splitBook$index", "拆分项目 ${index + 1}", bookChoices, optional = false)
                            input("splitAmount$index", "拆分金额 ${index + 1}", KeyboardType.Decimal)
                        }
                        if (bookChoices.size >= 2 && splitCount < bookChoices.size) {
                            TextButton(onClick = { splitCount = if (splitCount == 0) 2 else splitCount + 1 }) {
                                Text(if (splitCount == 0) "拆分到多个项目" else "再加一个拆分项目")
                            }
                        }
                        if (splitCount > 0) {
                            TextButton(onClick = { splitCount = if (splitCount <= 2) 0 else splitCount - 1 }) {
                                Text(if (splitCount <= 2) "使用单一项目" else "移除最后一个拆分")
                            }
                        }
                    }
                    WorkspaceEditorKind.LINK -> {
                        transaction("transaction", "第一笔流水")
                        transaction("target", "关联流水")
                        choice("link", "关联类型", WorkspaceLinkKind.entries.map { it.name to it.label }, optional = false)
                    }
                    WorkspaceEditorKind.PLAN -> {
                        input("title", "计划名称")
                        input("amount", "金额（人民币）", KeyboardType.Decimal)
                        choice(
                            "direction",
                            "收支",
                            listOf(TransactionType.EXPENSE.name to "支出", TransactionType.INCOME.name to "收入"),
                            optional = false,
                        )
                        input("date", "首次日期")
                        choice("repeat", "周期", WorkspaceRepeat.entries.map { it.name to it.label }, optional = false)
                        if (text("repeat") != WorkspaceRepeat.ONCE.name) {
                            input("count", "次数（0 表示持续）", KeyboardType.Number)
                        }
                        choice("account", "账户", accountChoices)
                        choice("book", "项目", bookChoices)
                        input("scenario", "试算情景（留空为基准）")
                    }
                    WorkspaceEditorKind.INBOX -> {
                        choice(
                            "kind",
                            "事项类型",
                            inboxManualKinds.map { it.name to it.label },
                            optional = false,
                        )
                        input("title", "事项名称")
                        input("note", "备注或票据信息", lines = 4)
                        input("date", "到期日（可选）")
                        input("amount", "金额（可选）", KeyboardType.Decimal)
                        transaction("transaction", "关联流水", optional = true)
                        choice("book", "项目", bookChoices)
                        editor.attachment?.let { attachment ->
                            TextButton(enabled = !operation.busy && !readingReceipt, onClick = {
                                readingReceipt = true
                                localError = null
                                scope.launch {
                                    try {
                                        val text = viewModel.readReceiptText(attachment)
                                        if (text.isBlank()) localError = "未读到文字，仍可保存原图。"
                                        else values["note"] = listOf(values["note"].orEmpty(), text)
                                            .filter { it.isNotBlank() }.distinct().joinToString("\n").take(2_000)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        localError = error.message ?: "暂时无法识别票据。"
                                    } finally {
                                        readingReceipt = false
                                    }
                                }
                            }) { Text(if (readingReceipt) "正在识别…" else "识别票据文字") }
                        }
                    }
                    WorkspaceEditorKind.RULE -> {
                        input("title", "规则名称")
                        input("contains", "备注包含")
                        choice(
                            "category",
                            "改为分类",
                            projection.categories.filterNot { it.id == "system-other" }.map { it.id to it.name },
                            optional = false,
                        )
                        choice("book", "项目", bookChoices)
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = text("disabled") != "true",
                                enabled = !operation.busy,
                                onCheckedChange = { values["disabled"] = (!it).toString() },
                            )
                            Text("启用规则")
                        }
                    }
                }
                (localError ?: operation.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !operation.busy && !readingReceipt,
                onClick = {
                    try {
                        localError = null
                        val id = editor.existingId ?: UUID.randomUUID().toString()
                        when (editor.kind) {
                            WorkspaceEditorKind.ACCOUNT -> viewModel.saveAccount(
                                WorkspaceAccount(optional("alias") ?: id, title(), text("currency").uppercase(), signedMoney()),
                            )
                            WorkspaceEditorKind.CHECKPOINT -> viewModel.saveCheckpoint(
                                WorkspaceCheckpoint(
                                    UUID.randomUUID().toString(),
                                    required("account", "请选择账户。"),
                                    localEndOfDay(date("date")),
                                    signedMoney(),
                                ),
                            )
                            WorkspaceEditorKind.BOOK -> viewModel.saveBook(
                                WorkspaceBook(id, title(), text("currency").uppercase()),
                            )
                            WorkspaceEditorKind.ASSIGNMENT -> viewModel.saveAssignment(
                                WorkspaceAssignment(
                                    transactionId = required("transaction", "请选择流水。"),
                                    accountId = optional("account"),
                                    bookId = optional("book").takeIf { splitCount == 0 },
                                    originalCurrency = optional("originalCurrency")?.uppercase(),
                                    originalAmountMinor = optional("originalAmount")?.let { money("originalAmount") },
                                    splits = List(splitCount) { i ->
                                        WorkspaceSplit(required("splitBook$i", "请选择拆分项目。"), money("splitAmount$i"))
                                    },
                                ),
                            )
                            WorkspaceEditorKind.LINK -> viewModel.saveLink(
                                WorkspaceLink(
                                    required("transaction", "请选择第一笔流水。"),
                                    required("target", "请选择关联流水。"),
                                    WorkspaceLinkKind.valueOf(text("link")),
                                ),
                            )
                            WorkspaceEditorKind.PLAN -> {
                                val repeat = WorkspaceRepeat.valueOf(text("repeat"))
                                val count = if (repeat == WorkspaceRepeat.ONCE) 1 else {
                                    text("count").toIntOrNull()?.takeIf { it >= 0 }
                                        ?: throw IllegalArgumentException("请输入非负次数。")
                                }
                                viewModel.savePlan(
                                    WorkspacePlan(
                                        id, title(), money("amount"), TransactionType.valueOf(text("direction")),
                                        date("date").toEpochDay(), repeat, count, optional("account"), optional("book"),
                                        optional("scenario"),
                                    ),
                                )
                            }
                            WorkspaceEditorKind.INBOX -> viewModel.saveInboxItem(
                                WorkspaceInboxItem(
                                    id = id,
                                    bookId = optional("book"),
                                    kind = WorkspaceInboxKind.valueOf(text("kind")),
                                    title = title(),
                                    note = text("note"),
                                    dueAtEpochMillis = optional("date")?.let { localEndOfDay(date("date")) } ?: 0,
                                    amountMinor = optional("amount")?.let { money("amount", allowZero = true) } ?: 0,
                                    transactionIds = optional("transaction")?.let { selected ->
                                        (listOf(selected) + previousItem?.transactionIds.orEmpty().drop(1)).distinct()
                                    }.orEmpty(),
                                    attachmentName = previousItem?.attachmentName,
                                    attachmentReference = previousItem?.attachmentReference,
                                    completed = previousItem?.completed ?: false,
                                ),
                                editor.attachment,
                            )
                            WorkspaceEditorKind.RULE -> viewModel.saveRule(
                                WorkspaceRule(
                                    id, optional("book"), title(), required("contains", "请填写需要匹配的备注。"),
                                    required("category", "请选择目标分类。"), text("disabled") != "true",
                                ),
                            )
                        }
                    } catch (error: IllegalArgumentException) {
                        localError = error.message ?: "请检查表单。"
                    }
                },
            ) { Text(if (operation.busy) "正在保存…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !operation.busy) { Text("取消") }
        },
    )
}

@Composable
internal fun WorkspaceChoice(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    optional: Boolean = true,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var opened by remember(label) { mutableStateOf(false) }
    val choice = options.firstOrNull { it.first == selected }?.second
    Box {
        OutlinedButton(onClick = { opened = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(if (choice == null) label else "$label · $choice", maxLines = 2)
        }
        DropdownMenu(
            expanded = opened,
            onDismissRequest = { opened = false },
            modifier = Modifier.heightIn(max = 275.dp),
        ) {
            if (optional) DropdownMenuItem(
                text = { Text("不指定") },
                onClick = {
                    opened = false
                    onSelect("")
                },
            )
            if (options.isEmpty()) DropdownMenuItem(text = { Text("暂无可选项") }, enabled = false, onClick = {})
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        opened = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkspaceTransactionChoice(
    transactions: List<Transaction>,
    selected: String,
    label: String,
    optional: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var opened by remember(label) { mutableStateOf(false) }
    var query by remember(label) { mutableStateOf("") }
    var results by remember(label, transactions) { mutableStateOf(transactions.take(20)) }
    LaunchedEffect(transactions, query, opened) {
        if (!opened) return@LaunchedEffect
        if (query.isNotBlank()) delay(120)
        val term = query.trim()
        results = withContext(Dispatchers.Default) {
            transactions.asSequence().filter {
                term.isEmpty() || it.note?.contains(term, ignoreCase = true) == true ||
                    it.accountId?.contains(term, ignoreCase = true) == true ||
                    (term.firstOrNull()?.isDigit() == true && localDateOf(it).toString().contains(term))
            }.take(25).toList()
        }
    }
    val title = remember(transactions, selected) { transactions.firstOrNull { it.id == selected }?.workspaceLabel }
    Box {
        OutlinedButton(onClick = { opened = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(title ?: label, maxLines = 2)
        }
        DropdownMenu(
            expanded = opened,
            onDismissRequest = { opened = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索日期、备注或账户") },
                singleLine = true,
            )
            if (optional) DropdownMenuItem(
                text = { Text("不指定") },
                onClick = {
                    opened = false
                    onSelect("")
                },
            )
            results.forEach { transaction ->
                DropdownMenuItem(
                    text = { Text(transaction.workspaceLabel, maxLines = 2) },
                    onClick = {
                        opened = false
                        onSelect(transaction.id)
                    },
                )
            }
        }
    }
}

internal val WorkspaceRepeat.label: String
    get() = when (this) {
        WorkspaceRepeat.ONCE -> "单次"
        WorkspaceRepeat.WEEKLY -> "每周"
        WorkspaceRepeat.MONTHLY -> "每月"
    }

internal val WorkspaceLinkKind.label: String
    get() = when (this) {
        WorkspaceLinkKind.DUPLICATE -> "同笔重复"
        WorkspaceLinkKind.TRANSFER -> "账户转账"
        WorkspaceLinkKind.REFUND -> "退款关联"
    }

internal val WorkspaceInboxKind.label: String
    get() = when (this) {
        WorkspaceInboxKind.RECEIPT -> "票据归档"
        WorkspaceInboxKind.REFUND -> "退款跟踪"
        WorkspaceInboxKind.REIMBURSEMENT -> "报销跟踪"
        WorkspaceInboxKind.WARRANTY -> "保修"
        WorkspaceInboxKind.RENEWAL -> "订阅续费"
        WorkspaceInboxKind.UNCATEGORIZED -> "待分类"
        WorkspaceInboxKind.DUPLICATE -> "疑似重复"
        WorkspaceInboxKind.PRICE_INCREASE -> "订阅涨价"
    }

private val inboxManualKinds = listOf(
    WorkspaceInboxKind.RECEIPT,
    WorkspaceInboxKind.REFUND,
    WorkspaceInboxKind.REIMBURSEMENT,
    WorkspaceInboxKind.WARRANTY,
    WorkspaceInboxKind.RENEWAL,
)

internal val Transaction.workspaceLabel: String
    get() {
        val sign = if (type == TransactionType.EXPENSE) "−" else "+"
        val memo = note?.take(28)?.let { " · $it" }.orEmpty()
        return "${localDateOf(this)} · $sign${com.financeos.app.ui.viewmodel.formatMoney(amount)}$memo"
    }

private fun localDateOf(transaction: Transaction): LocalDate =
    java.time.Instant.ofEpochMilli(transaction.dateTime.toEpochMilliseconds()).atZone(ZoneId.systemDefault()).toLocalDate()

private fun localEndOfDay(date: LocalDate): Long =
    date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

private fun inputAmount(amount: Long): String = if (amount >= 0) {
    amountInputFromMinorUnits(amount)
} else {
    "-${kotlin.math.abs(amount / 100)}.${kotlin.math.abs(amount % 100).toString().padStart(2, '0')}"
}

private fun parseSignedAmount(text: String): Long {
    val negative = text.startsWith('-')
    val magnitude = parseAmountInMinorUnits(text.removePrefix("-"), allowZero = true)
        ?: throw IllegalArgumentException("请输入有效余额，最多两位小数。")
    return if (negative) -magnitude else magnitude
}

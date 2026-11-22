package com.financeos.app.ui.screens

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.financeos.app.ui.viewmodel.MAX_PASTE_CHARACTERS
import com.financeos.app.ui.viewmodel.PendingImportUiState
import com.financeos.shared.data.transfer.table.StatementColumnMapping
import com.financeos.shared.data.transfer.table.StatementColumns

@Composable
internal fun PasteStatementDialog(onDismiss: () -> Unit, onPreview: (String) -> Unit) {
    val context = LocalContext.current
    var content by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    fun updateContent(text: String) {
        pasteError = if (text.length > MAX_PASTE_CHARACTERS) "内容过长，请改用文件导入。" else null
        if (pasteError == null) content = text
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴账单表格") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clip = clipboard?.primaryClip
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                        if (text.isNullOrBlank()) {
                            pasteError = "剪贴板里没有表格文本。"
                        } else if (text.length > MAX_PASTE_CHARACTERS) {
                            pasteError = "内容过长，请改用文件导入。"
                        } else {
                            onPreview(text)
                        }
                    },
                ) { Text("预览剪贴板账单") }
                OutlinedTextField(
                    value = content,
                    onValueChange = ::updateContent,
                    label = { Text("CSV 或从表格复制的内容") },
                    placeholder = { Text("日期,金额,备注") },
                    supportingText = { Text(pasteError ?: "首行填列名，后面每行一笔。默认负数为支出。") },
                    isError = pasteError != null,
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onPreview(content) }, enabled = content.isNotBlank() && pasteError == null) {
                Text("预览")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun ImportPreviewDialog(
    pending: PendingImportUiState,
    error: String?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onMapColumns: () -> Unit,
    onExchangeRate: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    var exchangeRate by remember(pending.sourceCurrencyCode) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text("导入预览 · ${pending.format}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pending.format == "FinanceOS JSON") {
                    Text("${pending.transactionCount} 笔流水 · ${pending.categoryCount} 个分类 · ${pending.budgetCount} 条预算")
                    Text("确认后按修改时间合并。备份里的删除记录也会同步。")
                } else {
                    Text(
                        "${pending.transactionCount} 行可导入 · ${pending.duplicateRows} 行文件内重复 · " +
                            "${pending.skippedRows} 行跳过 · ${pending.invalidRows} 行无效",
                    )
                    if (pending.samples.isNotEmpty()) {
                        Text("前 ${pending.samples.size} 笔", style = MaterialTheme.typography.labelLarge)
                        pending.samples.forEach { Text(it) }
                    }
                }
                if (pending.requiresCurrencyConversion && pending.transactionCount > 0) {
                    val currency = pending.sourceCurrencyCode.orEmpty()
                    OutlinedTextField(
                        value = exchangeRate,
                        onValueChange = { if (it.length <= 24) exchangeRate = it },
                        label = { Text("每 1 $currency 折合人民币") },
                        placeholder = { Text("例如 7.20") },
                        singleLine = true,
                        enabled = !isBusy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedButton(
                        onClick = { onExchangeRate(exchangeRate.replace(',', '.')) },
                        enabled = exchangeRate.isNotBlank() && !isBusy,
                    ) { Text("换算并更新预览") }
                }
                pending.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (pending.canMapColumns) {
                    OutlinedButton(onClick = onMapColumns, enabled = !isBusy) { Text("调整列映射") }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = pending.canImport && !isBusy) {
                Text(if (isBusy) "处理中…" else "确认导入")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isBusy) { Text("取消") } },
    )
}

@Composable
internal fun MapStatementColumnsDialog(
    columns: StatementColumns,
    issue: String?,
    hasPreview: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onPreview: (StatementColumnMapping) -> Unit,
) {
    var mapping by remember(columns) { mutableStateOf(columns.suggestedMapping) }
    var separateAmounts by remember(columns) {
        mutableStateOf(mapping.credit.isNotBlank() || mapping.debit.isNotBlank())
    }
    var showOptionalColumns by remember(columns) { mutableStateOf(false) }
    val headers = columns.headers
    val hasAmount = if (separateAmounts) {
        mapping.credit.isNotBlank() || mapping.debit.isNotBlank()
    } else {
        mapping.amount.isNotBlank()
    }
    val canPreview = mapping.date.isNotBlank() && hasAmount
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text("调整列映射") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ColumnChooser("日期", mapping.date, headers, isBusy) { mapping = mapping.copy(date = it) }
                if (separateAmounts) {
                    ColumnChooser("收入金额", mapping.credit, headers, isBusy) { mapping = mapping.copy(credit = it) }
                    ColumnChooser("支出金额", mapping.debit, headers, isBusy) { mapping = mapping.copy(debit = it) }
                } else {
                    ColumnChooser("金额", mapping.amount, headers, isBusy) { mapping = mapping.copy(amount = it) }
                    ColumnChooser("收支方向（可选）", mapping.direction, headers, isBusy) {
                        mapping = mapping.copy(direction = it)
                    }
                    if (mapping.direction.isBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = mapping.invertAmountSign,
                                enabled = !isBusy,
                                onCheckedChange = { mapping = mapping.copy(invertAmountSign = it) },
                            )
                            Text("正数代表支出")
                        }
                    }
                }
                TextButton(onClick = { separateAmounts = !separateAmounts }, enabled = !isBusy) {
                    Text(if (separateAmounts) "使用单列金额" else "分列指定收入和支出")
                }
                TextButton(onClick = { showOptionalColumns = !showOptionalColumns }, enabled = !isBusy) {
                    Text(if (showOptionalColumns) "收起其他列" else "设置备注、商户等可选列")
                }
                if (showOptionalColumns) {
                    ColumnChooser("备注", mapping.note, headers, isBusy) { mapping = mapping.copy(note = it) }
                    ColumnChooser("商户", mapping.merchant, headers, isBusy) { mapping = mapping.copy(merchant = it) }
                    ColumnChooser("分类", mapping.category, headers, isBusy) { mapping = mapping.copy(category = it) }
                    ColumnChooser("账户", mapping.account, headers, isBusy) { mapping = mapping.copy(account = it) }
                    ColumnChooser("单号", mapping.externalId, headers, isBusy) { mapping = mapping.copy(externalId = it) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val effectiveMapping = if (separateAmounts) {
                        mapping.copy(amount = "", direction = "", invertAmountSign = false)
                    } else {
                        mapping.copy(
                            credit = "",
                            debit = "",
                            invertAmountSign = mapping.invertAmountSign && mapping.direction.isBlank(),
                        )
                    }
                    onPreview(effectiveMapping)
                },
                enabled = canPreview && !isBusy,
            ) { Text(if (isBusy) "解析中…" else "更新预览") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text(if (hasPreview) "返回" else "取消") }
        },
    )
}

@Composable
private fun ColumnChooser(
    label: String,
    selection: String,
    headers: List<String>,
    isBusy: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text("$label · ${selection.ifBlank { "未选择" }}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            listOf("" to "未选择").plus(headers.map { it to it }).forEach { (value, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

package com.financeos.app.ui.screens

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeos.app.ui.components.FinanceCard
import com.financeos.app.ui.components.MonthSelector
import com.financeos.app.ui.viewmodel.AiAnalysisViewModel
import com.financeos.app.ui.viewmodel.CategoryReview
import com.financeos.app.ui.viewmodel.CategorySuggestionUiState
import com.financeos.shared.ai.FinanceAi
import com.financeos.shared.domain.model.TransactionType
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun AiAnalysisRoute(viewModel: AiAnalysisViewModel, onOpenChat: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isIdle = state.activity == null
    var showSettings by remember(state.isConfigured) { mutableStateOf(!state.isConfigured) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FinanceCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("AI 接口", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onOpenChat, enabled = isIdle) { Text("打开对话") }
                    if (state.isConfigured) {
                        val provider = remember(state.endpoint) { java.net.URI.create(state.endpoint).host }
                        Text("${state.model} · $provider", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { showSettings = !showSettings }, enabled = isIdle) {
                            Text(if (showSettings) "收起配置" else "修改配置")
                        }
                    }
                    if (showSettings) {
                        AiConfigurationForm(
                            savedEndpoint = state.endpoint,
                            savedModel = state.model,
                            configured = state.isConfigured,
                            enabled = isIdle,
                            onSave = viewModel::saveSettings,
                            onClear = viewModel::clearSettings,
                        )
                    }
                    if (state.activity != null && state.classificationProgress == null && state.categoryReview == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(state.activity.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        if (state.canCancel) {
                            TextButton(onClick = viewModel::cancel) { Text("取消本次请求") }
                        }
                    }
                    if (state.categoryReview == null) {
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item {
            FinanceCard {
                MonthSelector(
                    monthLabel = monthFormatter.format(state.month),
                    onPreviousMonth = viewModel::previousMonth,
                    onNextMonth = viewModel::nextMonth,
                    canShowPreviousMonth = isIdle,
                    canShowNextMonth = isIdle && state.canShowNextMonth,
                )
            }
        }
        item {
            FinanceCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("流水分类", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "每次最多分析当月 ${FinanceAi.MAX_CLASSIFICATION_TRANSACTIONS} 笔「其他」流水。短备注、收支方向和分类名称会分批发送至所选服务。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val progress = state.classificationProgress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.completed.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("已分析 ${progress.completed} / ${progress.total} 笔")
                        TextButton(onClick = viewModel::cancel) { Text("停止分析") }
                    } else {
                        Button(
                            onClick = viewModel::suggestCategories,
                            enabled = state.isConfigured && isIdle,
                        ) { Text("获取分类建议") }
                        state.classificationSummary?.let { summary ->
                            Text(summary, style = MaterialTheme.typography.bodyMedium)
                        }
                        state.classificationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        item {
            FinanceCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("消费与预算建议", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "点击后，会向所选服务发送当月收支、分类汇总与预算金额。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = viewModel::analyzeSpending,
                        enabled = state.isConfigured && isIdle,
                    ) { Text("分析月度消费") }
                    state.advice?.let { advice ->
                        Text(advice.summary, style = MaterialTheme.typography.bodyLarge)
                        advice.recommendations.forEach { recommendation ->
                            Text("• $recommendation", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        items(state.suggestions, key = { it.suggestion.transactionId }) { item ->
            CategorySuggestionCard(
                item = item,
                enabled = isIdle,
                onReview = { viewModel.reviewSuggestion(item) },
                onDismiss = { viewModel.dismiss(item) },
            )
        }
    }
    state.categoryReview?.let { review ->
        ConfirmCategoryDialog(
            review = review,
            enabled = isIdle,
            error = state.error,
            onDismiss = viewModel::dismissReview,
            onConfirm = viewModel::confirmCategory,
        )
    }
}

@Composable
private fun AiConfigurationForm(
    savedEndpoint: String,
    savedModel: String,
    configured: Boolean,
    enabled: Boolean,
    onSave: (String, String, String) -> Unit,
    onClear: () -> Unit,
) {
    var endpoint by remember(savedEndpoint) { mutableStateOf(savedEndpoint) }
    var model by remember(savedModel) { mutableStateOf(savedModel) }
    var key by remember { mutableStateOf("") }
    OutlinedTextField(
        value = endpoint,
        onValueChange = { endpoint = it },
        label = { Text("接口地址（HTTPS）") },
        supportingText = { Text("基础地址默认使用 Responses。兼容服务也可填写 /chat/completions 完整地址。") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine = true,
        enabled = enabled,
    )
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("模型名称") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
    )
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text(if (configured) "API 密钥（同地址可留空）" else "API 密钥（按服务要求）") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        enabled = enabled,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onSave(endpoint, model, key)
                key = ""
            },
            enabled = enabled && endpoint.isNotBlank() && model.isNotBlank(),
        ) { Text("保存配置") }
        OutlinedButton(
            onClick = {
                onClear()
                key = ""
            },
            enabled = enabled,
        ) { Text("清除配置") }
    }
}

@Composable
private fun CategorySuggestionCard(
    item: CategorySuggestionUiState,
    enabled: Boolean,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    FinanceCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${item.amountText} · ${item.dateLabel}", style = MaterialTheme.typography.titleMedium)
            Text("建议分类 · ${item.categoryName}", style = MaterialTheme.typography.bodyMedium)
            item.transaction.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (item.suggestion.reason.isNotBlank()) {
                Text(item.suggestion.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReview, enabled = enabled) { Text("确认分类") }
                TextButton(onClick = onDismiss, enabled = enabled) { Text("忽略") }
            }
        }
    }
}

@Composable
private fun ConfirmCategoryDialog(
    review: CategoryReview,
    enabled: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?, String?) -> Unit,
) {
    var isCreating by remember(review.item) { mutableStateOf(false) }
    var name by remember(review.item) { mutableStateOf("") }
    var query by remember(review.item) { mutableStateOf("") }
    var selectedId by remember(review.item) {
        mutableStateOf(review.options.firstOrNull { it.id == review.item.suggestion.categoryId }?.id)
    }
    val visibleOptions = remember(review.options, query) {
        val term = query.trim()
        review.options.filter { it.name.contains(term, ignoreCase = true) }
    }
    val longName = name.trim().length > 40
    val canConfirm = if (isCreating) {
        name.isNotBlank() && !longName
    } else {
        visibleOptions.any { it.id == selectedId }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCreating) "新建分类" else "确认分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${review.item.amountText} · ${review.item.dateLabel}")
                review.item.transaction.note?.let { Text(it, maxLines = 2) }
                if (isCreating) {
                    val direction = if (review.item.transaction.type == TransactionType.INCOME) "收入" else "支出"
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("分类名称") },
                        supportingText = {
                            Text(if (longName) "分类名称最多 40 个字符。" else "用于${direction}流水")
                        },
                        isError = longName,
                        singleLine = true,
                        enabled = enabled,
                    )
                } else {
                    if (review.options.size > 8) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("搜索分类") },
                            singleLine = true,
                            enabled = enabled,
                        )
                    }
                    if (visibleOptions.isEmpty()) {
                        Text("没有可选分类。")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 216.dp).selectableGroup(),
                        ) {
                            items(visibleOptions, key = { it.id }) { category ->
                                val selected = selectedId == category.id
                                Row(
                                    modifier = Modifier.fillMaxWidth().selectable(
                                        selected = selected,
                                        enabled = enabled,
                                        role = Role.RadioButton,
                                        onClick = { selectedId = category.id },
                                    ).heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    RadioButton(selected = selected, onClick = null, enabled = enabled)
                                    Text(category.name)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { isCreating = !isCreating }, enabled = enabled) {
                    Text(if (isCreating) "选择已有分类" else "新建分类…")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedId, name.takeIf { isCreating }) },
                enabled = enabled && canConfirm,
            ) { Text(if (enabled) "应用分类" else "正在保存…") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = enabled) { Text("取消") } },
    )
}

private val monthFormatter = DateTimeFormatter.ofPattern("uuuu年M月", Locale.SIMPLIFIED_CHINESE)

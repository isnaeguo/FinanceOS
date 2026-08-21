package com.financeos.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeos.app.ui.components.EmptyState
import com.financeos.app.ui.components.LoadingState
import com.financeos.app.ui.components.categoryIcon
import com.financeos.app.ui.viewmodel.BudgetEditorUiState
import com.financeos.app.ui.viewmodel.BudgetEvent
import com.financeos.app.ui.viewmodel.BudgetUiState
import com.financeos.app.ui.viewmodel.BudgetUsageUiState
import com.financeos.app.ui.viewmodel.BudgetViewModel
import com.financeos.app.ui.viewmodel.CategoryBudgetUiState

/** 连接预算 ViewModel 与无数据依赖的页面内容。 */
@Composable
internal fun BudgetRoute(viewModel: BudgetViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BudgetEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        BudgetScreen(
            uiState = uiState,
            onRetry = viewModel::refresh,
            onEditTotal = viewModel::openTotalBudgetEditor,
            onAddCategory = viewModel::openNewCategoryBudgetEditor,
            onEditCategory = viewModel::openCategoryBudgetEditor,
            onEditorCategorySelected = viewModel::onEditorCategorySelected,
            onEditorAmountChanged = viewModel::onEditorAmountChanged,
            onEditorSave = viewModel::saveEditor,
            onEditorDismiss = viewModel::dismissEditor,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 当前月总预算和分类预算页面，指标只展示 ViewModel 已准备好的 UseCase 结果。 */
@Composable
internal fun BudgetScreen(
    uiState: BudgetUiState,
    onRetry: () -> Unit,
    onEditTotal: () -> Unit,
    onAddCategory: () -> Unit,
    onEditCategory: (String) -> Unit,
    onEditorCategorySelected: (String) -> Unit,
    onEditorAmountChanged: (String) -> Unit,
    onEditorSave: () -> Unit,
    onEditorDismiss: () -> Unit,
) {
    when {
        uiState.isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingState(label = "正在读取预算")
            }
        }

        uiState.errorMessage != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = "暂时无法读取预算",
                    description = uiState.errorMessage,
                    actionLabel = "重试",
                    onAction = onRetry,
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        text = uiState.monthLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                item {
                    Text("月总预算", style = MaterialTheme.typography.titleLarge)
                }

                uiState.total?.let { total ->
                    item {
                        if (total.hasBudget) {
                            BudgetUsageCard(
                                title = "本月总预算",
                                usage = total,
                                onEdit = onEditTotal,
                            )
                        } else {
                            MissingTotalBudgetCard(
                                amountUsedText = total.amountUsedText,
                                onSetBudget = onEditTotal,
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("分类预算", style = MaterialTheme.typography.titleLarge)
                        TextButton(
                            onClick = onAddCategory,
                            enabled = uiState.canAddCategoryBudget,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text("新增")
                        }
                    }
                }

                if (uiState.categoryBudgets.isEmpty()) {
                    item {
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("尚未设置分类预算", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "为餐饮、交通等分类设置独立额度，更容易发现具体支出压力。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = onAddCategory,
                                    enabled = uiState.canAddCategoryBudget,
                                ) {
                                    Text("新增分类预算")
                                }
                            }
                        }
                    }
                } else {
                    items(
                        items = uiState.categoryBudgets,
                        key = CategoryBudgetUiState::categoryId,
                    ) { categoryBudget ->
                        CategoryBudgetCard(
                            budget = categoryBudget,
                            onEdit = { onEditCategory(categoryBudget.categoryId) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(160),
                                placementSpec = tween(180, easing = FastOutSlowInEasing),
                                fadeOutSpec = tween(120),
                            ),
                        )
                    }
                }
            }
        }
    }

    uiState.editor?.let { editor ->
        BudgetEditorDialog(
            editor = editor,
            onCategorySelected = onEditorCategorySelected,
            onAmountChanged = onEditorAmountChanged,
            onSave = onEditorSave,
            onDismiss = onEditorDismiss,
        )
    }
}

@Composable
private fun MissingTotalBudgetCard(
    amountUsedText: String,
    onSetBudget: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("尚未设置本月总预算", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "本月已使用 $amountUsedText。设置预算后即可查看剩余额度和使用比例。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSetBudget) {
                Text("设置月总预算")
            }
        }
    }
}

@Composable
private fun CategoryBudgetCard(
    budget: CategoryBudgetUiState,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = categoryIcon(budget.categoryIconKey),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Text(
                    text = budget.categoryName,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onEdit) {
                    Text("修改")
                }
            }
            BudgetUsageContent(usage = budget.usage)
        }
    }
}

@Composable
private fun BudgetUsageCard(
    title: String,
    usage: BudgetUsageUiState,
    onEdit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onEdit) {
                    Text("修改")
                }
            }
            BudgetUsageContent(usage = usage)
        }
    }
}

@Composable
private fun BudgetUsageContent(usage: BudgetUsageUiState) {
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BudgetMetric("预算金额", usage.amountLimitText, horizontal = true)
            BudgetMetric("已使用", usage.amountUsedText, horizontal = true)
            BudgetMetric("剩余", usage.amountRemainingText, horizontal = true)
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth()) {
            BudgetMetric(
                label = "预算金额",
                value = usage.amountLimitText,
                modifier = Modifier.weight(1f),
            )
            BudgetMetric(
                label = "已使用",
                value = usage.amountUsedText,
                modifier = Modifier.weight(1f),
            )
            BudgetMetric(
                label = "剩余",
                value = usage.amountRemainingText,
                modifier = Modifier.weight(1f),
            )
        }
    }
    val animatedProgress by animateFloatAsState(
        targetValue = usage.progress,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "budgetProgress",
    )
    LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = Modifier.fillMaxWidth(),
        color = if (usage.isOverBudget) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "使用比例 ${usage.usageRatioText}",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        BudgetStatusLabel(usage)
    }
}

@Composable
private fun BudgetMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
) {
    if (horizontal) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BudgetMetricText(label = label, value = value)
        }
    } else {
        Column(modifier = modifier) {
            BudgetMetricText(label = label, value = value)
        }
    }
}

@Composable
private fun BudgetMetricText(label: String, value: String) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
    Text(
        text = value,
        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
        maxLines = 1,
    )
}

@Composable
private fun BudgetStatusLabel(usage: BudgetUsageUiState) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (usage.isOverBudget) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (usage.isOverBudget) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = usage.statusText,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun BudgetEditorDialog(
    editor: BudgetEditorUiState,
    onCategorySelected: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val amountFocusRequester = remember { FocusRequester() }
    var categoryMenuExpanded by remember(editor.title) { mutableStateOf(false) }

    LaunchedEffect(editor.title) {
        amountFocusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(editor.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editor.isNewCategoryBudget) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { categoryMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val selectedName = editor.categoryOptions
                                .firstOrNull { it.id == editor.selectedCategoryId }
                                ?.name
                                ?: "选择分类"
                            Text(selectedName)
                        }
                        DropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false },
                        ) {
                            editor.categoryOptions.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.name) },
                                    onClick = {
                                        onCategorySelected(category.id)
                                        categoryMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = editor.amountInput,
                    onValueChange = onAmountChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(amountFocusRequester),
                    label = { Text("预算金额") },
                    placeholder = { Text("0.00") },
                    prefix = { Text("¥") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = editor.amountError != null,
                    supportingText = editor.amountError?.let { message ->
                        { Text(message) }
                    },
                    singleLine = true,
                )

                editor.saveError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = editor.canSave,
            ) {
                if (editor.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !editor.isSaving) {
                Text("取消")
            }
        },
    )
}

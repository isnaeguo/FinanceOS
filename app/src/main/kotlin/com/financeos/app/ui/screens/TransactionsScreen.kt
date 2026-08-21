package com.financeos.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeos.app.ui.components.categoryIcon
import com.financeos.app.ui.components.EmptyState
import com.financeos.app.ui.viewmodel.TransactionItemUiState
import com.financeos.app.ui.viewmodel.TransactionsEvent
import com.financeos.app.ui.viewmodel.TransactionsUiState
import com.financeos.app.ui.viewmodel.TransactionsViewModel

/** 连接流水 ViewModel 与页面内容。 */
@Composable
internal fun TransactionsRoute(
    viewModel: TransactionsViewModel,
    onAddTransaction: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TransactionsEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    TransactionsScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onAddTransaction = onAddTransaction,
        onRetry = viewModel::refresh,
        onPreviousMonth = viewModel::showPreviousMonth,
        onNextMonth = viewModel::showNextMonth,
        onDeleteRequested = viewModel::requestDelete,
        onDeleteConfirmed = viewModel::confirmDelete,
        onDeleteDismissed = viewModel::dismissDelete,
    )
}

/** 展示指定月份的真实流水，并通过确认对话框避免误删。 */
@Composable
internal fun TransactionsScreen(
    uiState: TransactionsUiState,
    snackbarHostState: SnackbarHostState,
    onAddTransaction: () -> Unit,
    onRetry: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDeleteRequested: (String) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MonthSelector(
                monthLabel = uiState.monthLabel,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
            )
            HorizontalDivider()

            Box(modifier = Modifier.weight(1f)) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    uiState.errorMessage != null -> {
                        EmptyState(
                            title = "暂时无法读取流水",
                            description = uiState.errorMessage,
                            modifier = Modifier.align(Alignment.Center),
                            actionLabel = "重试",
                            onAction = onRetry,
                        )
                    }

                    uiState.items.isEmpty() -> {
                        EmptyState(
                            title = "本月暂无流水",
                            description = "${uiState.monthLabel}还没有记录，记下第一笔收入或支出吧。",
                            modifier = Modifier.align(Alignment.Center),
                            actionLabel = "记一笔",
                            onAction = onAddTransaction,
                        )
                    }

                    else -> {
                        TransactionList(
                            items = uiState.items,
                            onDeleteRequested = onDeleteRequested,
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    uiState.pendingDeleteItem?.let { item ->
        DeleteTransactionDialog(
            item = item,
            isDeleting = uiState.isDeleting,
            onConfirm = onDeleteConfirmed,
            onDismiss = onDeleteDismissed,
        )
    }
}

@Composable
private fun MonthSelector(
    monthLabel: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPreviousMonth) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月")
        }
        Text(text = monthLabel, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNextMonth) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下个月")
        }
    }
}

@Composable
private fun TransactionList(
    items: List<TransactionItemUiState>,
    onDeleteRequested: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            items = items,
            key = TransactionItemUiState::id,
        ) { item ->
            ListItem(
                leadingContent = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = categoryIcon(item.categoryIconKey),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                },
                headlineContent = { Text(item.categoryName) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item.note?.let { note ->
                            Text(note, maxLines = 2)
                        }
                        // 明确显示“收入/支出”文字及正负号，不让方向信息只依赖颜色。
                        Text(
                            text = "${item.typeLabel} · ${item.dateTimeText}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = item.amountText,
                            color = if (item.isExpense) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = { onDeleteRequested(item.id) }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "删除${item.categoryName}流水",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

@Composable
private fun DeleteTransactionDialog(
    item: TransactionItemUiState,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val description = buildString {
        append("确定删除“${item.categoryName}")
        item.note?.let { append(" · $it") }
        append("”吗？删除后无法恢复。")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除流水？") },
        text = { Text(description) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("删除")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text("取消")
            }
        },
    )
}

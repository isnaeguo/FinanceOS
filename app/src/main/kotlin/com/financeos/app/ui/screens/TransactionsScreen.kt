package com.financeos.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeos.app.ui.components.EmptyState
import com.financeos.app.ui.viewmodel.TransactionItemUiState
import com.financeos.app.ui.viewmodel.TransactionsUiState
import com.financeos.app.ui.viewmodel.TransactionsViewModel

/** 连接流水 ViewModel 与页面内容。 */
@Composable
internal fun TransactionsRoute(
    viewModel: TransactionsViewModel,
    onAddTransaction: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TransactionsScreen(
        uiState = uiState,
        onAddTransaction = onAddTransaction,
        onRetry = viewModel::refresh,
    )
}

/** 展示数据库中的真实流水，空数据与加载失败均提供明确反馈。 */
@Composable
internal fun TransactionsScreen(
    uiState: TransactionsUiState,
    onAddTransaction: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        uiState.isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        uiState.errorMessage != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = "暂时无法读取流水",
                    description = uiState.errorMessage,
                    actionLabel = "重试",
                    onAction = onRetry,
                )
            }
        }

        uiState.items.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = "暂无流水",
                    description = "记录第一笔收入或支出后，历史流水会显示在这里。",
                    actionLabel = "记一笔",
                    onAction = onAddTransaction,
                )
            }
        }

        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = uiState.items,
                    key = TransactionItemUiState::id,
                ) { item ->
                    ListItem(
                        headlineContent = { Text(item.categoryName) },
                        supportingContent = {
                            Text(
                                listOfNotNull(item.dateTimeText, item.note)
                                    .joinToString(" · "),
                            )
                        },
                        trailingContent = {
                            Text(
                                text = item.amountText,
                                color = if (item.isExpense) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

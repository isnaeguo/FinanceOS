package com.financeos.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeos.app.ui.components.MonthSelector
import com.financeos.app.ui.components.categoryIcon
import com.financeos.app.ui.components.EmptyState
import com.financeos.app.ui.components.LoadingState
import com.financeos.app.ui.viewmodel.AccountFilter
import com.financeos.app.ui.viewmodel.AmountSort
import com.financeos.app.ui.viewmodel.TransactionItemUiState
import com.financeos.app.ui.viewmodel.TransactionFilterOption
import com.financeos.app.ui.viewmodel.TransactionsEvent
import com.financeos.app.ui.viewmodel.TransactionsUiState
import com.financeos.app.ui.viewmodel.TransactionsViewModel
import com.financeos.shared.domain.model.TransactionType
import com.financeos.app.ui.components.CategoryBadge
import androidx.compose.ui.graphics.Color

/** 连接流水 ViewModel 与页面内容。 */
@Composable
internal fun TransactionsRoute(
    viewModel: TransactionsViewModel,
    onAddTransaction: () -> Unit,
    onEditTransaction: (String) -> Unit,
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
        onSearchQueryChange = viewModel::updateSearchQuery,
        onTypeSelected = viewModel::selectType,
        onCategorySelected = viewModel::selectCategory,
        onAccountSelected = viewModel::selectAccount,
        onSortSelected = viewModel::selectAmountSort,
        onClearFilters = viewModel::clearFilters,
        onDeleteRequested = viewModel::requestDelete,
        onDeleteConfirmed = viewModel::confirmDelete,
        onDeleteDismissed = viewModel::dismissDelete,
        onEditTransaction = onEditTransaction,
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
    onSearchQueryChange: (String) -> Unit,
    onTypeSelected: (TransactionType?) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onAccountSelected: (AccountFilter) -> Unit,
    onSortSelected: (AmountSort) -> Unit,
    onClearFilters: () -> Unit,
    onDeleteRequested: (String) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
    onEditTransaction: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MonthSelector(
                monthLabel = uiState.monthLabel,
                canShowNextMonth = uiState.canShowNextMonth,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
            )
            TransactionFilters(
                searchQuery = uiState.searchQuery,
                selectedType = uiState.selectedType,
                selectedCategoryId = uiState.selectedCategoryId,
                selectedAccount = uiState.selectedAccount,
                amountSort = uiState.amountSort,
                categoryOptions = uiState.categoryOptions,
                accountOptions = uiState.accountOptions,
                hasActiveFilters = uiState.hasActiveFilters,
                onSearchQueryChange = onSearchQueryChange,
                onTypeSelected = onTypeSelected,
                onCategorySelected = onCategorySelected,
                onAccountSelected = onAccountSelected,
                onSortSelected = onSortSelected,
                onClearFilters = onClearFilters,
            )
            HorizontalDivider()

            Box(modifier = Modifier.weight(1f)) {
                when {
                    uiState.isLoading -> {
                        LoadingState(
                            label = "正在读取流水",
                            modifier = Modifier.align(Alignment.Center),
                        )
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

                    uiState.items.isEmpty() && uiState.hasActiveFilters -> {
                        EmptyState(
                            title = "没有匹配的流水",
                            description = "试试缩短备注关键词，或调整分类、账户和收支条件。",
                            modifier = Modifier.align(Alignment.Center),
                            actionLabel = "清除筛选",
                            onAction = onClearFilters,
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
                            onEditTransaction = onEditTransaction,
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
private fun TransactionFilters(
    searchQuery: String,
    selectedType: TransactionType?,
    selectedCategoryId: String?,
    selectedAccount: AccountFilter,
    amountSort: AmountSort,
    categoryOptions: List<TransactionFilterOption>,
    accountOptions: List<TransactionFilterOption>,
    hasActiveFilters: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onTypeSelected: (TransactionType?) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onAccountSelected: (AccountFilter) -> Unit,
    onSortSelected: (AmountSort) -> Unit,
    onClearFilters: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            label = { Text("搜索备注、分类或账户") },
            placeholder = { Text("例如：午饭") },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "清除搜索")
                    }
                }
            } else {
                null
            },
            singleLine = true,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            item {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { onTypeSelected(null) },
                    label = { Text("全部") },
                )
            }
            item {
                FilterChip(
                    selected = selectedType == TransactionType.EXPENSE,
                    onClick = { onTypeSelected(TransactionType.EXPENSE) },
                    label = { Text("支出") },
                )
            }
            item {
                FilterChip(
                    selected = selectedType == TransactionType.INCOME,
                    onClick = { onTypeSelected(TransactionType.INCOME) },
                    label = { Text("收入") },
                )
            }
            item {
                FilterOptionMenu(
                    label = selectedCategoryId?.let { selectedId ->
                        categoryOptions.firstOrNull { it.id == selectedId }?.label
                    } ?: "全部分类",
                    selected = selectedCategoryId != null,
                    allLabel = "全部分类",
                    options = categoryOptions,
                    onAllSelected = { onCategorySelected(null) },
                    onSelected = { onCategorySelected(it.id) },
                )
            }
            item {
                val accountLabel = when (val account = selectedAccount) {
                    AccountFilter.All -> "全部账户"
                    AccountFilter.Unspecified -> "未指定账户"
                    is AccountFilter.Specific -> account.accountId
                }
                FilterOptionMenu(
                    label = accountLabel,
                    selected = selectedAccount != AccountFilter.All,
                    allLabel = "全部账户",
                    includeUnspecified = true,
                    options = accountOptions,
                    onAllSelected = { onAccountSelected(AccountFilter.All) },
                    onUnspecifiedSelected = { onAccountSelected(AccountFilter.Unspecified) },
                    onSelected = { onAccountSelected(AccountFilter.Specific(it.id)) },
                )
            }
            item {
                AmountSortMenu(
                    selected = amountSort,
                    onSelected = onSortSelected,
                )
            }
            if (hasActiveFilters) {
                item {
                    TextButton(onClick = onClearFilters) {
                        Text("清除")
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterOptionMenu(
    label: String,
    selected: Boolean,
    allLabel: String,
    options: List<TransactionFilterOption>,
    onAllSelected: () -> Unit,
    onSelected: (TransactionFilterOption) -> Unit,
    includeUnspecified: Boolean = false,
    onUnspecifiedSelected: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { expanded = true },
            label = { Text(label) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                onClick = {
                    expanded = false
                    onAllSelected()
                },
            )
            if (includeUnspecified) {
                DropdownMenuItem(
                    text = { Text("未指定账户") },
                    onClick = {
                        expanded = false
                        onUnspecifiedSelected()
                    },
                )
            }
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun AmountSortMenu(
    selected: AmountSort,
    onSelected: (AmountSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = { Text("排序：${selected.label}") },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AmountSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun TransactionList(
    items: List<TransactionItemUiState>,
    onDeleteRequested: (String) -> Unit,
    onEditTransaction: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        items(items, key = TransactionItemUiState::id) { item ->
            TransactionRow(
                item = item,
                onEdit = { onEditTransaction(item.id) },
                onDelete = { onDeleteRequested(item.id) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun TransactionRow(item: TransactionItemUiState, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember(item.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "编辑流水", onClick = onEdit),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            CategoryBadge {
                Icon(categoryIcon(item.categoryIconKey), contentDescription = null, tint = colors.onSecondaryContainer)
            }
        },
        headlineContent = { Text(item.categoryName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item.note?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(
                    text = item.detailText,
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.amountText,
                    color = if (item.isExpense) colors.onSurface else colors.primary,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "流水操作")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = colors.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = colors.error) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    )
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

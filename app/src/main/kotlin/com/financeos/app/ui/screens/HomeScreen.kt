package com.financeos.app.ui.screens

import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.financeos.app.ui.components.EmptyState
import com.financeos.app.ui.components.categoryIcon
import com.financeos.app.ui.viewmodel.DashboardCategoryUiState
import com.financeos.app.ui.viewmodel.DashboardTransactionUiState
import com.financeos.app.ui.viewmodel.DashboardUiState
import com.financeos.app.ui.viewmodel.DashboardViewModel

/** 连接 Dashboard ViewModel 与无数据依赖的首页内容。 */
@Composable
internal fun HomeRoute(
    viewModel: DashboardViewModel,
    onOpenBudget: () -> Unit,
    onOpenTransactions: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 跨过零点后回到前台时重新取得本地日期，让新的日预算及时生效。
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    HomeScreen(
        uiState = uiState,
        onRetry = viewModel::refresh,
        onOpenBudget = onOpenBudget,
        onOpenTransactions = onOpenTransactions,
    )
}

/** FinanceOS 日常首页，优先呈现当月支出、剩余预算与今日可用金额。 */
@Composable
internal fun HomeScreen(
    uiState: DashboardUiState,
    onRetry: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenTransactions: () -> Unit,
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
                    title = "暂时无法读取首页数据",
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
                    start = 20.dp,
                    top = 16.dp,
                    end = 20.dp,
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
                    MonthlyOverviewCard(uiState)
                }
                item {
                    DashboardMetrics(uiState)
                }
                item {
                    Text(
                        text = uiState.dailyAvailableExplanation,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    BudgetProgressCard(
                        uiState = uiState,
                        onOpenBudget = onOpenBudget,
                    )
                }
                item {
                    SectionTitle(title = "主要花在哪里")
                }
                item {
                    TopCategoriesCard(categories = uiState.topCategories)
                }
                item {
                    SectionTitle(
                        title = "最近流水",
                        actionLabel = "查看全部",
                        onAction = onOpenTransactions,
                    )
                }
                item {
                    RecentTransactionsCard(transactions = uiState.recentTransactions)
                }
            }
        }
    }
}

@Composable
private fun DashboardMetrics(uiState: DashboardUiState) {
    val stackMetrics = LocalDensity.current.fontScale >= 1.5f
    val remainingBudgetCard: @Composable (Modifier) -> Unit = { modifier ->
        DashboardMetricCard(
            label = "剩余预算",
            value = uiState.remainingBudgetText,
            supportingText = if (uiState.hasBudget) "本月剩余额度" else "尚未设置预算",
            isWarning = uiState.isOverBudget,
            modifier = modifier,
        )
    }
    val dailyAvailableCard: @Composable (Modifier) -> Unit = { modifier ->
        DashboardMetricCard(
            label = "今日建议预算",
            value = uiState.dailyAvailableText,
            supportingText = "当天固定，次日重算",
            isWarning = uiState.isOverBudget,
            modifier = modifier,
        )
    }

    if (stackMetrics) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            remainingBudgetCard(Modifier.fillMaxWidth())
            dailyAvailableCard(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            remainingBudgetCard(Modifier.weight(1f))
            dailyAvailableCard(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MonthlyOverviewCard(uiState: DashboardUiState) {
    val stackExpenses = LocalDensity.current.fontScale >= 1.5f
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (stackExpenses) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpenseMetric(
                        label = "本月支出",
                        value = uiState.monthlyExpenseText,
                        isPrimary = true,
                    )
                    ExpenseMetric(
                        label = "本日支出",
                        value = uiState.dailyExpenseText,
                        isPrimary = false,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ExpenseMetric(
                        label = "本月支出",
                        value = uiState.monthlyExpenseText,
                        isPrimary = true,
                        modifier = Modifier.weight(1f),
                    )
                    ExpenseMetric(
                        label = "本日支出",
                        value = uiState.dailyExpenseText,
                        isPrimary = false,
                        modifier = Modifier.weight(1f),
                        alignment = Alignment.End,
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "本月收入",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                AnimatedAmountText(
                    text = uiState.monthlyIncomeText,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun ExpenseMetric(
    label: String,
    value: String,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
        AnimatedAmountText(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = if (isPrimary) {
                MaterialTheme.typography.headlineLarge
            } else {
                MaterialTheme.typography.headlineSmall
            },
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DashboardMetricCard(
    label: String,
    value: String,
    supportingText: String,
    isWarning: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            AnimatedAmountText(
                text = value,
                color = if (isWarning) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AnimatedAmountText(
    text: String,
    color: Color,
    style: TextStyle,
    fontWeight: FontWeight? = null,
) {
    Crossfade(
        targetState = text,
        animationSpec = tween(durationMillis = AMOUNT_CROSSFADE_DURATION_MILLIS),
        label = "dashboardAmount",
    ) { amount ->
        Text(
            text = amount,
            color = color,
            style = style,
            fontWeight = fontWeight,
        )
    }
}

@Composable
private fun BudgetProgressCard(
    uiState: DashboardUiState,
    onOpenBudget: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("预算进度", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onOpenBudget) {
                    Text(if (uiState.hasBudget) "管理" else "去设置")
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                    )
                }
            }
            if (uiState.hasBudget) {
                LinearProgressIndicator(
                    progress = { uiState.budgetProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (uiState.isOverBudget) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (uiState.isOverBudget) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = uiState.budgetProgressText,
                    color = if (uiState.isOverBudget) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!uiState.hasBudget) {
                Button(onClick = onOpenBudget) {
                    Text("设置月总预算")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun TopCategoriesCard(categories: List<DashboardCategoryUiState>) {
    if (categories.isEmpty()) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "本月还没有支出，开始记账后会显示消费最多的分类。",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            categories.forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                                imageVector = categoryIcon(category.categoryIconKey),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(category.categoryName, style = MaterialTheme.typography.bodyLarge)
                            Text(category.amountText, style = MaterialTheme.typography.bodyMedium)
                        }
                        LinearProgressIndicator(
                            progress = { category.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = category.shareText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentTransactionsCard(transactions: List<DashboardTransactionUiState>) {
    if (transactions.isEmpty()) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "暂无最近流水。点击“记一笔”后，新记录会出现在这里。",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            transactions.forEachIndexed { index, transaction ->
                RecentTransactionRow(transaction)
                if (index != transactions.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun RecentTransactionRow(transaction: DashboardTransactionUiState) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = categoryIcon(transaction.categoryIconKey),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
        headlineContent = { Text(transaction.categoryName) },
        supportingContent = {
            Text(
                listOfNotNull(transaction.note, transaction.dateTimeText).joinToString(" · "),
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = transaction.amountText,
                    color = if (transaction.isExpense) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = transaction.typeLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
    )
}

private const val AMOUNT_CROSSFADE_DURATION_MILLIS = 100

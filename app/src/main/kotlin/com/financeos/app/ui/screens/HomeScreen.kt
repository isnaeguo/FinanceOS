package com.financeos.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.financeos.app.ui.components.FinanceCard
import com.financeos.app.ui.components.CategoryBadge
import com.financeos.app.ui.components.EmptyState
import com.financeos.app.ui.components.LoadingState
import com.financeos.app.ui.components.MonthSelector
import com.financeos.app.ui.components.categoryIcon
import com.financeos.app.ui.viewmodel.DashboardCategoryUiState
import com.financeos.app.ui.viewmodel.DashboardTransactionUiState
import com.financeos.app.ui.viewmodel.DashboardUiState
import com.financeos.app.ui.viewmodel.DashboardViewModel
import com.financeos.app.ui.viewmodel.DashboardTrendPointUiState
import com.financeos.app.ui.viewmodel.SpendingTrendRange

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
        viewModel.refreshIfDateChanged()
        onPauseOrDispose { }
    }

    HomeScreen(
        uiState = uiState,
        onRetry = viewModel::refresh,
        onOpenBudget = onOpenBudget,
        onOpenTransactions = onOpenTransactions,
        onTrendRangeSelected = viewModel::selectSpendingTrendRange,
        onPreviousMonth = viewModel::previousMonth,
        onNextMonth = viewModel::nextMonth,
    )
}

/** FinanceOS 日常首页，默认展示当月支出、剩余预算与今日可用金额，也可回看更早月份。 */
@Composable
internal fun HomeScreen(
    uiState: DashboardUiState,
    onRetry: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenTransactions: () -> Unit,
    onTrendRangeSelected: (SpendingTrendRange) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    when {
        uiState.isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingState(label = "正在整理本月财务数据")
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
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    MonthSelector(
                        monthLabel = uiState.monthLabel,
                        canShowPreviousMonth = uiState.canGoPrevious,
                        canShowNextMonth = uiState.canGoNext,
                        onPreviousMonth = onPreviousMonth,
                        onNextMonth = onNextMonth,
                    )
                }
                item {
                    MonthlyOverviewCard(uiState)
                }
                item {
                    BudgetProgressCard(
                        uiState = uiState,
                        onOpenBudget = onOpenBudget,
                    )
                }
                item {
                    MonthlyTrendCard(points = uiState.monthlyExpenseTrend)
                }
                item {
                    SpendingTrendCard(
                        points = uiState.dailyExpenseTrend,
                        range = uiState.spendingTrendRange,
                        onRangeSelected = onTrendRangeSelected,
                    )
                }
                item {
                    SectionTitle(title = "分类消费排行")
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
                if (uiState.recentTransactions.isEmpty()) {
                    item {
                        FinanceCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "暂无最近流水。",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(uiState.recentTransactions, key = DashboardTransactionUiState::id) { transaction ->
                        RecentTransactionRow(transaction)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyTrendCard(points: List<DashboardTrendPointUiState>) {
    FinanceCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("近 6 个月净支出", style = MaterialTheme.typography.titleMedium)
            if (points.none { it.normalizedAmount != 0f }) {
                Text("各月净支出均为零。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val stackRows = LocalDensity.current.fontScale >= 1.5f
            points.forEach { point ->
                if (stackRows) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(point.label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            Text(point.amountText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                        SignedTrendBar(point.normalizedAmount, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            point.label,
                            modifier = Modifier.weight(0.16f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        SignedTrendBar(point.normalizedAmount, Modifier.weight(0.54f))
                        Text(
                            point.amountText,
                            modifier = Modifier.weight(0.3f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignedTrendBar(amount: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val barColor = if (amount < 0f) colors.secondary else colors.primary
    Box(
        modifier = modifier
            .height(10.dp)
            .drawBehind {
                val center = Offset(size.width / 2, size.height / 2)
                val barWidth = 4.dp.toPx()
                drawLine(colors.surfaceVariant, Offset(0f, center.y), Offset(size.width, center.y), barWidth)
                if (amount != 0f) {
                    drawLine(barColor, center, Offset(center.x * (1f + amount), center.y), barWidth)
                }
                drawLine(colors.outline, Offset(center.x, 0f), Offset(center.x, size.height), 1.dp.toPx())
            },
    )
}

@Composable
private fun SpendingTrendCard(
    points: List<DashboardTrendPointUiState>,
    range: SpendingTrendRange,
    onRangeSelected: (SpendingTrendRange) -> Unit,
) {
    FinanceCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("近期净支出趋势", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpendingTrendRange.entries.forEach { option ->
                        FilterChip(
                            selected = range == option,
                            onClick = { onRangeSelected(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
            if (points.none { it.normalizedAmount != 0f }) {
                Text(
                    text = "${range.label}净支出为零。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ExpenseLineChart(points)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(points.first().label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Text(points.last().label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ExpenseLineChart(points: List<DashboardTrendPointUiState>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .semantics {
                contentDescription = "净支出趋势，从 ${points.first().label} 到 ${points.last().label}。零线下方为净收入。"
            }
            .drawWithCache {
                val minAmount = minOf(0f, points.minOf { it.normalizedAmount })
                val maxAmount = maxOf(0f, points.maxOf { it.normalizedAmount })
                val inset = 4.dp.toPx()
                val chartHeight = (size.height - 2 * inset).coerceAtLeast(0f)
                fun y(amount: Float): Float = inset + (maxAmount - amount) / (maxAmount - minAmount) * chartHeight
                val baselineY = y(0f)
                val coordinates = points.mapIndexed { index, point ->
                    val x = if (points.size == 1) size.width / 2 else size.width * index / points.lastIndex
                    Offset(x, y(point.normalizedAmount))
                }
                val path = Path().apply {
                    val first = coordinates.first()
                    moveTo(first.x, first.y)
                    for (index in 1..coordinates.lastIndex) {
                        val point = coordinates[index]
                        lineTo(point.x, point.y)
                    }
                }
                val markers = coordinates.filterIndexed { index, _ ->
                    points.size <= 7 || index % 5 == 0 || index == coordinates.lastIndex
                }
                val lineStroke = Stroke(2.dp.toPx())
                val baselineWidth = 1.dp.toPx()
                val markerRadius = 2.5.dp.toPx()
                onDrawBehind {
                    drawLine(baselineColor, Offset(0f, baselineY), Offset(size.width, baselineY), baselineWidth)
                    drawPath(path, color = lineColor, style = lineStroke)
                    markers.forEach { drawCircle(lineColor, markerRadius, center = it) }
                }
            },
    )
}

@Composable
private fun BudgetSummaryMetrics(uiState: DashboardUiState) {
    val stacked = LocalDensity.current.fontScale >= 1.5f
    if (uiState.isCurrentMonth && !stacked) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DashboardMetric("剩余预算", uiState.remainingBudgetText, uiState.isOverBudget, Modifier.weight(1f))
            DashboardMetric("今日建议预算", uiState.dailyAvailableText, uiState.isOverBudget, Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DashboardMetric("剩余预算", uiState.remainingBudgetText, uiState.isOverBudget)
            if (uiState.isCurrentMonth) {
                DashboardMetric("今日建议预算", uiState.dailyAvailableText, uiState.isOverBudget)
            }
        }
    }
}

@Composable
private fun MonthlyOverviewCard(uiState: DashboardUiState) {
    val stackExpenses = LocalDensity.current.fontScale >= 1.5f
    val monthReference = monthReferenceLabel(uiState)
    FinanceCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (stackExpenses) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpenseMetric(
                        label = "${monthReference}净支出",
                        value = uiState.monthlyExpenseText,
                        isPrimary = true,
                    )
                    if (uiState.isCurrentMonth) {
                        ExpenseMetric(
                            label = "本日支出",
                            value = uiState.dailyExpenseText,
                            isPrimary = false,
                        )
                    }
                }
            } else if (uiState.isCurrentMonth) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    ExpenseMetric(
                        label = "${monthReference}净支出",
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
            } else {
                ExpenseMetric(
                    label = "${monthReference}净支出",
                    value = uiState.monthlyExpenseText,
                    isPrimary = true,
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${monthReference}收入",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                AmountText(
                    text = uiState.monthlyIncomeText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        AmountText(
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
private fun DashboardMetric(
    label: String,
    value: String,
    isWarning: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        AmountText(
            text = value,
            color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AmountText(
    text: String,
    color: Color,
    style: TextStyle,
    fontWeight: FontWeight? = null,
) {
    Text(text = text, color = color, style = style.copy(fontFeatureSettings = "tnum"), fontWeight = fontWeight)
}

@Composable
private fun BudgetProgressCard(
    uiState: DashboardUiState,
    onOpenBudget: () -> Unit,
) {
    FinanceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("预算", style = MaterialTheme.typography.titleMedium)
                if (uiState.hasBudget) {
                    TextButton(onClick = onOpenBudget) {
                        Text("管理")
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                        )
                    }
                }
            }
            if (uiState.hasBudget) {
                BudgetSummaryMetrics(uiState)
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
            if (uiState.isCurrentMonth && uiState.hasBudget) {
                Text(
                    text = uiState.dailyAvailableExplanation,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
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
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TopCategoriesCard(categories: List<DashboardCategoryUiState>) {
    if (categories.isEmpty()) {
        FinanceCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "本月还没有支出，开始记账后会显示消费最多的分类。",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            categories.forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CategoryBadge(
                        size = 40.dp,
                    ) {
                        Icon(
                            imageVector = categoryIcon(category.categoryIconKey),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(category.categoryName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            Text(category.amountText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun RecentTransactionRow(transaction: DashboardTransactionUiState) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            CategoryBadge(
                size = 40.dp,
            ) {
                Icon(
                    imageVector = categoryIcon(transaction.categoryIconKey),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
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

/** 当前展示月份的口语称谓：本月用“本月”，回看历史月用具体的“N月”短格式。 */
private fun monthReferenceLabel(uiState: DashboardUiState): String =
    if (uiState.isCurrentMonth) "本月" else uiState.monthLabel.substringAfter('年', uiState.monthLabel)

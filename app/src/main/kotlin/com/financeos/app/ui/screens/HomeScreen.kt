package com.financeos.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
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
                    DashboardMetrics(uiState)
                }
                if (uiState.isCurrentMonth) {
                    item {
                        Text(
                            text = uiState.dailyAvailableExplanation,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    BudgetProgressCard(
                        uiState = uiState,
                        onOpenBudget = onOpenBudget,
                    )
                }
                item {
                    SectionTitle(title = "近 6 个月支出")
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
                item {
                    RecentTransactionsCard(transactions = uiState.recentTransactions)
                }
            }
        }
    }
}

@Composable
private fun MonthlyTrendCard(points: List<DashboardTrendPointUiState>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (points.isEmpty() || points.all { it.progress == 0f }) {
            Text(
                text = "最近 6 个月还没有支出趋势。",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Surface
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val stackRows = LocalDensity.current.fontScale >= 1.5f
            points.forEach { point ->
                val animatedProgress by animateFloatAsState(
                    targetValue = point.progress,
                    animationSpec = tween(
                        durationMillis = CHART_ANIMATION_DURATION_MILLIS,
                        easing = FastOutSlowInEasing,
                    ),
                    label = "monthlyTrend",
                )
                if (stackRows) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(point.label, style = MaterialTheme.typography.labelMedium)
                            Text(point.amountText, style = MaterialTheme.typography.labelMedium)
                        }
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.weight(0.54f),
                        )
                        Text(
                            point.amountText,
                            modifier = Modifier.weight(0.3f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpendingTrendCard(
    points: List<DashboardTrendPointUiState>,
    range: SpendingTrendRange,
    onRangeSelected: (SpendingTrendRange) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("近期消费趋势", style = MaterialTheme.typography.titleMedium)
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
            if (points.isEmpty() || points.all { it.progress == 0f }) {
                Text(
                    text = "${range.label}还没有支出。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ExpenseLineChart(points)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(points.first().label, style = MaterialTheme.typography.labelSmall)
                    Text(points.last().label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ExpenseLineChart(points: List<DashboardTrendPointUiState>) {
    val animation = remember { Animatable(0f) }
    val path = remember { Path() }
    val lineColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    LaunchedEffect(points) {
        animation.snapTo(0f)
        animation.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = CHART_ANIMATION_DURATION_MILLIS,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .semantics {
                contentDescription = "支出趋势，从 ${points.first().label} 到 ${points.last().label}"
            },
    ) {
        val baselineY = size.height - 4.dp.toPx()
        drawLine(
            color = baselineColor,
            start = androidx.compose.ui.geometry.Offset(0f, baselineY),
            end = androidx.compose.ui.geometry.Offset(size.width, baselineY),
            strokeWidth = 1.dp.toPx(),
        )
        path.reset()
        points.forEachIndexed { index, point ->
            val x = if (points.size == 1) {
                size.width / 2f
            } else {
                size.width * index.toFloat() / points.lastIndex.toFloat()
            }
            val y = baselineY - point.progress * animation.value * (baselineY - 8.dp.toPx())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx()),
        )
        points.forEachIndexed { index, point ->
            if (points.size > 7 && index % 5 != 0 && index != points.lastIndex) {
                return@forEachIndexed
            }
            val x = if (points.size == 1) size.width / 2f
            else size.width * index.toFloat() / points.lastIndex.toFloat()
            val y = baselineY - point.progress * animation.value * (baselineY - 8.dp.toPx())
            drawCircle(lineColor, radius = 2.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}

@Composable
private fun DashboardMetrics(uiState: DashboardUiState) {
    val stackMetrics = LocalDensity.current.fontScale >= 1.5f
    val monthReference = monthReferenceLabel(uiState)
    val remainingBudgetCard: @Composable (Modifier) -> Unit = { modifier ->
        DashboardMetricCard(
            label = "剩余预算",
            value = uiState.remainingBudgetText,
            supportingText = if (uiState.hasBudget) "${monthReference}剩余额度" else "尚未设置预算",
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

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (uiState.isCurrentMonth && stackMetrics) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                remainingBudgetCard(Modifier.fillMaxWidth())
                HorizontalDivider()
                dailyAvailableCard(Modifier.fillMaxWidth())
            }
        } else if (uiState.isCurrentMonth) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                remainingBudgetCard(Modifier.weight(1f))
                dailyAvailableCard(Modifier.weight(1f))
            }
        } else {
            // 回看过去月份时不展示“今日建议预算”，只保留剩余预算，避免把过去的预算当成今天的可用额度。
            Column(modifier = Modifier.padding(16.dp)) {
                remainingBudgetCard(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MonthlyOverviewCard(uiState: DashboardUiState) {
    val stackExpenses = LocalDensity.current.fontScale >= 1.5f
    val monthReference = monthReferenceLabel(uiState)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (stackExpenses) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpenseMetric(
                        label = "${monthReference}支出",
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
                        label = "${monthReference}支出",
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
                    label = "${monthReference}支出",
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
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AnimatedAmountText(
                    text = uiState.monthlyIncomeText,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
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
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
            style = MaterialTheme.typography.titleMedium,
        )
        AnimatedAmountText(
            text = value,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
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
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
            style = style.copy(fontFeatureSettings = "tnum"),
            fontWeight = fontWeight,
        )
    }
}

@Composable
private fun BudgetProgressCard(
    uiState: DashboardUiState,
    onOpenBudget: () -> Unit,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = uiState.budgetProgress,
        animationSpec = tween(
            durationMillis = CHART_ANIMATION_DURATION_MILLIS,
            easing = FastOutSlowInEasing,
        ),
        label = "dashboardBudgetProgress",
    )
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                LinearProgressIndicator(
                    progress = { animatedProgress },
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
        Text(text = title, style = MaterialTheme.typography.titleMedium)
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
                        val animatedProgress by animateFloatAsState(
                            targetValue = category.progress,
                            animationSpec = tween(
                                durationMillis = CHART_ANIMATION_DURATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                            label = "categoryProgress",
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
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

    Column(modifier = Modifier.fillMaxWidth()) {
            transactions.forEachIndexed { index, transaction ->
                RecentTransactionRow(transaction)
                if (index != transactions.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
    }
}

@Composable
private fun RecentTransactionRow(transaction: DashboardTransactionUiState) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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

private const val AMOUNT_CROSSFADE_DURATION_MILLIS = 180
private const val CHART_ANIMATION_DURATION_MILLIS = 220

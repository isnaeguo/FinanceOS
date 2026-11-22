package com.financeos.app.navigation

import android.animation.ValueAnimator
import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.financeos.app.FinanceOsApplication
import com.financeos.app.FinanceEntry
import com.financeos.app.ui.components.WorkspaceGlyph
import com.financeos.app.ui.components.WorkspaceSymbol
import com.financeos.app.workspace.WorkspaceRoute
import com.financeos.app.workspace.WorkspaceViewModel
import com.financeos.app.ui.screens.AddTransactionRoute
import com.financeos.app.ui.screens.AiAnalysisRoute
import com.financeos.app.ui.screens.BudgetRoute
import com.financeos.app.ui.screens.HomeRoute
import com.financeos.app.ui.screens.LanShareRoute
import com.financeos.app.ui.screens.SettingsRoute
import com.financeos.app.ui.screens.TransactionsRoute
import com.financeos.app.ui.viewmodel.AddTransactionViewModel
import com.financeos.app.ui.viewmodel.AiAnalysisViewModel
import com.financeos.app.ui.viewmodel.BudgetViewModel
import com.financeos.app.ui.viewmodel.DashboardViewModel
import com.financeos.app.ui.viewmodel.DataTransferViewModel
import com.financeos.app.ui.viewmodel.LanShareViewModel
import com.financeos.app.ui.viewmodel.TransactionsViewModel
import kotlinx.coroutines.launch

private const val HOME_ROUTE = "home"
private const val TRANSACTIONS_ROUTE = "transactions"
private const val ADD_TRANSACTION_ROUTE = "add-transaction"
private const val EDIT_TRANSACTION_ROUTE = "edit-transaction/{transactionId}"
private const val BUDGET_ROUTE = "budget"
private const val SETTINGS_ROUTE = "settings"
private const val AI_ROUTE = "ai-analysis"
private const val LAN_SHARE_ROUTE = "lan-share"
private const val WORKSPACE_ROUTE = "workspace"
private const val WORKSPACE_CHAT_ROUTE = "workspace-chat"
private const val COMPACT_FAB_FONT_SCALE = 1.3f

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(HOME_ROUTE, "总览", Icons.Default.Home),
    TopLevelDestination(TRANSACTIONS_ROUTE, "流水", Icons.AutoMirrored.Filled.List),
    TopLevelDestination(BUDGET_ROUTE, "预算", Icons.Default.Star),
    TopLevelDestination(WORKSPACE_ROUTE, "工作台", Icons.Default.CheckCircle),
)

/** FinanceOS Android 的单一导航入口，集中维护顶层与次级页面关系。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceOsNavigation(entry: FinanceEntry?, onEntryHandled: (FinanceEntry) -> Unit) {
    val navController = rememberNavController()
    val activityViewModels = checkNotNull(LocalViewModelStoreOwner.current)
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val application = LocalContext.current.applicationContext as FinanceOsApplication
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route ?: HOME_ROUTE
    val useCompactFab = LocalDensity.current.fontScale >= COMPACT_FAB_FONT_SCALE
    val showAddTransactionFab = currentRoute == HOME_ROUTE || currentRoute == TRANSACTIONS_ROUTE
    val isTopLevelDestination = topLevelDestinations.any { destination ->
        currentDestination?.hierarchy?.any { it.route == destination.route } == true
    } || backStackEntry == null

    LaunchedEffect(entry, currentRoute) {
        val requested = entry ?: return@LaunchedEffect
        val destination = when (requested) {
            FinanceEntry.AddTransaction -> ADD_TRANSACTION_ROUTE
            FinanceEntry.OpenWorkspace, is FinanceEntry.ReceiptImage -> WORKSPACE_ROUTE
            is FinanceEntry.StatementFile, is FinanceEntry.StatementText -> SETTINGS_ROUTE
            is FinanceEntry.Unsupported -> {
                snackbarHostState.showSnackbar(requested.message)
                onEntryHandled(requested)
                return@LaunchedEffect
            }
        }
        if (currentRoute != destination) {
            navController.navigate(destination) { launchSingleTop = true }
        }
        if (requested == FinanceEntry.AddTransaction || requested == FinanceEntry.OpenWorkspace) {
            onEntryHandled(requested)
        }
    }

    val colors = MaterialTheme.colorScheme
    val allowMotion = ValueAnimator.areAnimatorsEnabled()
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                title = {
                    if (currentRoute == HOME_ROUTE) {
                        Text("FINANCEOS", letterSpacing = 2.4.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text(titleForRoute(currentRoute))
                    }
                },
                navigationIcon = {
                    if (!isTopLevelDestination) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                actions = {
                    if (currentRoute == TRANSACTIONS_ROUTE) {
                        TextButton(onClick = { navController.navigate(SETTINGS_ROUTE) }) { Text("导入账单") }
                        TextButton(onClick = { navController.navigate(AI_ROUTE) }) { Text("AI 分析") }
                    }
                    if (currentRoute == HOME_ROUTE) {
                        IconButton(onClick = { navController.navigate(SETTINGS_ROUTE) }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "设置",
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isTopLevelDestination) {
                NavigationBar(
                    containerColor = colors.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(HOME_ROUTE) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (destination.route == WORKSPACE_ROUTE) {
                                    WorkspaceGlyph(WorkspaceSymbol.INBOX)
                                } else {
                                    Icon(imageVector = destination.icon, contentDescription = null)
                                }
                            },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = colors.onPrimaryContainer,
                                selectedTextColor = colors.onSurface,
                                indicatorColor = colors.primaryContainer,
                                unselectedIconColor = colors.onSurfaceVariant,
                                unselectedTextColor = colors.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (showAddTransactionFab) {
                val onAdd = { navController.navigate(ADD_TRANSACTION_ROUTE) }
                val elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 0.dp,
                    hoveredElevation = 0.dp,
                )
                if (useCompactFab) {
                    FloatingActionButton(
                        onClick = onAdd,
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                        elevation = elevation,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "记一笔")
                    }
                } else {
                    ExtendedFloatingActionButton(
                        onClick = onAdd,
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("记一笔") },
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                        elevation = elevation,
                    )
                }
            }
        },
        content = { contentPadding ->
            NavHost(
                navController = navController,
                startDestination = HOME_ROUTE,
                modifier = Modifier
                    .padding(contentPadding)
                    .consumeWindowInsets(contentPadding),
                enterTransition = { if (allowMotion) fadeIn(tween(110)) else EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                composable(HOME_ROUTE) {
                    val dashboardViewModel: DashboardViewModel = viewModel(
                        factory = application.container.dashboardViewModelFactory,
                    )
                    HomeRoute(
                        viewModel = dashboardViewModel,
                        onOpenBudget = { navController.navigate(BUDGET_ROUTE) },
                        onOpenTransactions = { navController.navigate(TRANSACTIONS_ROUTE) },
                    )
                }
                composable(TRANSACTIONS_ROUTE) {
                    val transactionsViewModel: TransactionsViewModel = viewModel(
                        factory = application.container.transactionsViewModelFactory,
                    )
                    TransactionsRoute(
                        viewModel = transactionsViewModel,
                        onAddTransaction = { navController.navigate(ADD_TRANSACTION_ROUTE) },
                        onEditTransaction = { transactionId ->
                            navController.navigate("edit-transaction/${Uri.encode(transactionId)}") {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(ADD_TRANSACTION_ROUTE) {
                    val addTransactionViewModel: AddTransactionViewModel = viewModel(
                        factory = application.container.addTransactionViewModelFactory(),
                    )
                    AddTransactionRoute(
                        viewModel = addTransactionViewModel,
                        onSaved = {
                            navController.popBackStack()
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("流水已保存")
                            }
                        },
                    )
                }
                composable(EDIT_TRANSACTION_ROUTE) { entry ->
                    val transactionId = entry.arguments?.getString("transactionId") ?: return@composable
                    val editor: AddTransactionViewModel = viewModel(
                        factory = application.container.addTransactionViewModelFactory(transactionId),
                    )
                    AddTransactionRoute(
                        viewModel = editor,
                        focusAmount = false,
                        onSaved = {
                            navController.popBackStack()
                            coroutineScope.launch { snackbarHostState.showSnackbar("流水已更新") }
                        },
                    )
                }
                composable(BUDGET_ROUTE) {
                    val budgetViewModel: BudgetViewModel = viewModel(
                        factory = application.container.budgetViewModelFactory,
                    )
                    BudgetRoute(viewModel = budgetViewModel)
                }
                composable(SETTINGS_ROUTE) {
                    val dataTransferViewModel: DataTransferViewModel = viewModel(
                        factory = application.container.dataTransferViewModelFactory,
                    )
                    SettingsRoute(
                        viewModel = dataTransferViewModel,
                        onOpenLanShare = { navController.navigate(LAN_SHARE_ROUTE) },
                        onOpenAi = { navController.navigate(AI_ROUTE) },
                        entry = entry,
                        onEntryHandled = onEntryHandled,
                    )
                }
                composable(AI_ROUTE) {
                    val aiAnalysisViewModel: AiAnalysisViewModel = viewModel(
                        factory = application.container.aiAnalysisViewModelFactory,
                    )
                    AiAnalysisRoute(
                        viewModel = aiAnalysisViewModel,
                        onOpenChat = { navController.navigate(WORKSPACE_CHAT_ROUTE) { launchSingleTop = true } },
                    )
                }
                listOf(WORKSPACE_ROUTE, WORKSPACE_CHAT_ROUTE).forEach { route ->
                    composable(route) {
                        val workspaceViewModel: WorkspaceViewModel = viewModel(
                            viewModelStoreOwner = activityViewModels,
                            factory = application.container.workspaceViewModelFactory,
                        )
                        WorkspaceRoute(
                            viewModel = workspaceViewModel,
                            receiptEntry = (entry as? FinanceEntry.ReceiptImage).takeIf { route == WORKSPACE_ROUTE },
                            onEntryHandled = onEntryHandled,
                            onEditTransaction = { id ->
                                navController.navigate("edit-transaction/${Uri.encode(id)}") { launchSingleTop = true }
                            },
                            onAiSettings = {
                                if (!navController.popBackStack(AI_ROUTE, inclusive = false)) {
                                    navController.navigate(AI_ROUTE) { launchSingleTop = true }
                                }
                            },
                            startInChat = route == WORKSPACE_CHAT_ROUTE,
                        )
                    }
                }
                composable(LAN_SHARE_ROUTE) {
                    val lanShareViewModel: LanShareViewModel = viewModel(
                        factory = application.container.lanShareViewModelFactory,
                    )
                    LanShareRoute(viewModel = lanShareViewModel)
                }
            }
        },
    )
}

private fun titleForRoute(route: String): String = when (route) {
    HOME_ROUTE -> "FinanceOS"
    TRANSACTIONS_ROUTE -> "流水"
    ADD_TRANSACTION_ROUTE -> "记一笔"
    EDIT_TRANSACTION_ROUTE -> "编辑流水"
    BUDGET_ROUTE -> "预算"
    SETTINGS_ROUTE -> "设置"
    AI_ROUTE -> "AI 账单分析"
    LAN_SHARE_ROUTE -> "局域网共享"
    WORKSPACE_ROUTE -> "财务工作台"
    WORKSPACE_CHAT_ROUTE -> "财务对话"
    else -> "FinanceOS"
}

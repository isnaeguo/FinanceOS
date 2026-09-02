package com.financeos.app.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.financeos.app.FinanceOsApplication
import com.financeos.app.ui.screens.AddTransactionRoute
import com.financeos.app.ui.screens.BudgetRoute
import com.financeos.app.ui.screens.HomeRoute
import com.financeos.app.ui.screens.LanShareRoute
import com.financeos.app.ui.screens.SettingsRoute
import com.financeos.app.ui.screens.TransactionsRoute
import com.financeos.app.ui.viewmodel.AddTransactionViewModel
import com.financeos.app.ui.viewmodel.BudgetViewModel
import com.financeos.app.ui.viewmodel.DashboardViewModel
import com.financeos.app.ui.viewmodel.DataTransferViewModel
import com.financeos.app.ui.viewmodel.LanShareViewModel
import com.financeos.app.ui.viewmodel.TransactionsViewModel
import kotlinx.coroutines.launch

private const val HOME_ROUTE = "home"
private const val TRANSACTIONS_ROUTE = "transactions"
private const val ADD_TRANSACTION_ROUTE = "add-transaction"
private const val BUDGET_ROUTE = "budget"
private const val SETTINGS_ROUTE = "settings"
private const val LAN_SHARE_ROUTE = "lan-share"
private const val TOP_LEVEL_FADE_DURATION_MILLIS = 140
private const val SCREEN_ENTER_DURATION_MILLIS = 180
private const val SCREEN_EXIT_DURATION_MILLIS = 120
private const val COMPACT_FAB_FONT_SCALE = 1.3f

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(HOME_ROUTE, "首页", Icons.Default.Home),
    TopLevelDestination(TRANSACTIONS_ROUTE, "流水", Icons.AutoMirrored.Filled.List),
    TopLevelDestination(BUDGET_ROUTE, "预算", Icons.Default.Star),
)

/** FinanceOS Android 的单一导航入口，集中维护顶层与次级页面关系。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceOsNavigation() {
    val navController = rememberNavController()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleForRoute(currentRoute)) },
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
                NavigationBar {
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
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            androidx.compose.animation.AnimatedVisibility(
                visible = showAddTransactionFab,
                enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.9f),
                exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.9f),
            ) {
                if (useCompactFab) {
                    FloatingActionButton(
                        onClick = { navController.navigate(ADD_TRANSACTION_ROUTE) },
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "记一笔")
                    }
                } else {
                    ExtendedFloatingActionButton(
                        onClick = { navController.navigate(ADD_TRANSACTION_ROUTE) },
                        icon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) },
                        text = { Text("记一笔") },
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
                enterTransition = {
                    if (initialState.destination.route.isTopLevelRoute() &&
                        targetState.destination.route.isTopLevelRoute()
                    ) {
                        fadeIn(tween(TOP_LEVEL_FADE_DURATION_MILLIS))
                    } else {
                        // 次级页面只移动很短距离，保留层级感但避免默认转场的拖沓感。
                        fadeIn(
                            tween(
                                durationMillis = SCREEN_ENTER_DURATION_MILLIS,
                                easing = LinearOutSlowInEasing,
                            ),
                        ) + slideInHorizontally(
                            animationSpec = tween(
                                durationMillis = SCREEN_ENTER_DURATION_MILLIS,
                                easing = LinearOutSlowInEasing,
                            ),
                            initialOffsetX = { fullWidth -> fullWidth / 12 },
                        )
                    }
                },
                exitTransition = {
                    val isTopLevel = initialState.destination.route.isTopLevelRoute() &&
                        targetState.destination.route.isTopLevelRoute()
                    if (isTopLevel) {
                        fadeOut(tween(TOP_LEVEL_FADE_DURATION_MILLIS))
                    } else {
                        fadeOut(
                            tween(
                                durationMillis = SCREEN_EXIT_DURATION_MILLIS,
                                easing = FastOutLinearInEasing,
                            ),
                        ) + slideOutHorizontally(
                            animationSpec = tween(SCREEN_EXIT_DURATION_MILLIS),
                            targetOffsetX = { fullWidth -> -fullWidth / 24 },
                        )
                    }
                },
                popEnterTransition = {
                    fadeIn(
                        tween(
                            durationMillis = SCREEN_ENTER_DURATION_MILLIS,
                            easing = LinearOutSlowInEasing,
                        ),
                    ) + slideInHorizontally(
                        animationSpec = tween(
                            durationMillis = SCREEN_ENTER_DURATION_MILLIS,
                            easing = LinearOutSlowInEasing,
                        ),
                        initialOffsetX = { fullWidth -> -fullWidth / 12 },
                    )
                },
                popExitTransition = {
                    fadeOut(
                        tween(
                            durationMillis = SCREEN_EXIT_DURATION_MILLIS,
                            easing = FastOutLinearInEasing,
                        ),
                    ) + slideOutHorizontally(
                        animationSpec = tween(SCREEN_EXIT_DURATION_MILLIS),
                        targetOffsetX = { fullWidth -> fullWidth / 24 },
                    )
                },
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
                    )
                }
                composable(ADD_TRANSACTION_ROUTE) {
                    val addTransactionViewModel: AddTransactionViewModel = viewModel(
                        factory = application.container.addTransactionViewModelFactory,
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
                    )
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

private fun String?.isTopLevelRoute(): Boolean =
    topLevelDestinations.any { destination -> destination.route == this }

private fun titleForRoute(route: String): String = when (route) {
    HOME_ROUTE -> "FinanceOS"
    TRANSACTIONS_ROUTE -> "流水"
    ADD_TRANSACTION_ROUTE -> "记一笔"
    BUDGET_ROUTE -> "预算"
    SETTINGS_ROUTE -> "设置"
    LAN_SHARE_ROUTE -> "局域网共享"
    else -> "FinanceOS"
}

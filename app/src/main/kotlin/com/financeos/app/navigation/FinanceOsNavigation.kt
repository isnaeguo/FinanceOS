package com.financeos.app.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.financeos.app.FinanceOsApplication
import com.financeos.app.ui.screens.AddTransactionRoute
import com.financeos.app.ui.screens.BudgetScreen
import com.financeos.app.ui.screens.HomeScreen
import com.financeos.app.ui.screens.SettingsScreen
import com.financeos.app.ui.screens.TransactionsRoute
import com.financeos.app.ui.viewmodel.AddTransactionViewModel
import com.financeos.app.ui.viewmodel.TransactionsViewModel

private const val HOME_ROUTE = "home"
private const val TRANSACTIONS_ROUTE = "transactions"
private const val ADD_TRANSACTION_ROUTE = "add-transaction"
private const val BUDGET_ROUTE = "budget"
private const val SETTINGS_ROUTE = "settings"
private const val TOP_LEVEL_FADE_DURATION_MILLIS = 90
private const val SCREEN_ENTER_DURATION_MILLIS = 140
private const val SCREEN_EXIT_DURATION_MILLIS = 70

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
    val application = LocalContext.current.applicationContext as FinanceOsApplication
    val transactionsViewModel: TransactionsViewModel = viewModel(
        factory = application.container.transactionsViewModelFactory,
    )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route ?: HOME_ROUTE
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
        floatingActionButton = {
            if (isTopLevelDestination) {
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate(ADD_TRANSACTION_ROUTE) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                        )
                    },
                    text = { Text("记一笔") },
                )
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
                    fadeOut(
                        tween(
                            durationMillis = SCREEN_EXIT_DURATION_MILLIS,
                            easing = FastOutLinearInEasing,
                        ),
                    )
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
                    )
                },
            ) {
                composable(HOME_ROUTE) {
                    HomeScreen()
                }
                composable(TRANSACTIONS_ROUTE) {
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
                            // Repository 当前是一次性读取；保存后主动刷新，再返回即可立即看到新流水。
                            transactionsViewModel.refresh()
                            navController.popBackStack()
                        },
                    )
                }
                composable(BUDGET_ROUTE) {
                    BudgetScreen()
                }
                composable(SETTINGS_ROUTE) {
                    SettingsScreen()
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
    else -> "FinanceOS"
}

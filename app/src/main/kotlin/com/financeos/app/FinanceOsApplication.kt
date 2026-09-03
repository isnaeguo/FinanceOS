package com.financeos.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.financeos.app.data.FinanceDataBridge
import com.financeos.app.ui.viewmodel.AddTransactionViewModel
import com.financeos.app.ui.viewmodel.BudgetViewModel
import com.financeos.app.ui.viewmodel.DashboardViewModel
import com.financeos.app.ui.viewmodel.DataTransferViewModel
import com.financeos.app.ui.viewmodel.LanShareViewModel
import com.financeos.app.ui.viewmodel.TransactionsViewModel
import com.financeos.app.data.AndroidDocumentStore
import com.financeos.shared.data.local.repository.LocalBudgetRepository
import com.financeos.shared.data.local.repository.LocalCategoryRepository
import com.financeos.shared.data.local.repository.LocalFinanceDataRepository
import com.financeos.shared.data.local.repository.LocalTransactionRepository
import com.financeos.shared.data.transfer.FinanceDataTransferService
import com.financeos.shared.domain.usecase.AddTransactionUseCase
import com.financeos.shared.domain.usecase.CalculateDailyAvailableBudgetUseCase
import com.financeos.shared.domain.usecase.CalculateDailyExpenseUseCase
import com.financeos.shared.domain.usecase.GetBudgetStatusUseCase
import com.financeos.shared.domain.usecase.GetExpenseTrendUseCase
import com.financeos.shared.domain.usecase.GetMonthlySummaryUseCase
import com.financeos.shared.domain.usecase.GetMonthlyTransactionsUseCase

/** 持有与应用进程同生命周期的本地数据依赖。 */
class FinanceOsApplication : Application() {
    internal val container: FinanceOsAppContainer by lazy {
        FinanceOsAppContainer(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        // shared 局域网同步的 Android 存取需要 Application context。
        com.financeos.shared.lansync.AppContextHolder.context = applicationContext
        // 数据变更后广播刷新主屏小组件，避免等待系统 30 分钟周期。
        com.financeos.app.widget.WidgetRefresher.ensure(this)
    }
}

/**
 * FinanceOS 的最小依赖容器。
 *
 * 当前依赖数量仍然很少，手动组装能让依赖方向清晰，也避免仅为少量对象引入 DI 框架。
 */
internal class FinanceOsAppContainer(context: Context) {
    // 通过 FinanceDataBridge 取库，让小组件 / 局域网共享与 Compose 页面共享同一数据库实例。
    private val database = FinanceDataBridge.get(context).database
    private val transactionRepository = LocalTransactionRepository(database.transactionDao())
    private val categoryRepository = LocalCategoryRepository(database.categoryDao())
    private val budgetRepository = LocalBudgetRepository(database.budgetDao())
    private val addTransactionUseCase = AddTransactionUseCase(
        transactionRepository = transactionRepository,
        categoryRepository = categoryRepository,
    )
    private val getMonthlyTransactionsUseCase = GetMonthlyTransactionsUseCase(transactionRepository)
    private val getMonthlySummaryUseCase = GetMonthlySummaryUseCase(getMonthlyTransactionsUseCase)
    private val getBudgetStatusUseCase = GetBudgetStatusUseCase(
        getMonthlySummary = getMonthlySummaryUseCase,
        budgetRepository = budgetRepository,
    )
    private val calculateDailyAvailableBudgetUseCase = CalculateDailyAvailableBudgetUseCase(
        budgetRepository = budgetRepository,
        getMonthlyTransactions = getMonthlyTransactionsUseCase,
    )
    private val calculateDailyExpenseUseCase = CalculateDailyExpenseUseCase()
    private val getExpenseTrendUseCase = GetExpenseTrendUseCase(transactionRepository)
    private val localFinanceDataRepository = LocalFinanceDataRepository(database)
    private val financeDataTransferService = FinanceDataTransferService(
        repository = localFinanceDataRepository,
    )
    private val documentStore = AndroidDocumentStore(context)

    val addTransactionViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            AddTransactionViewModel(
                addTransactionUseCase = addTransactionUseCase,
                categoryRepository = categoryRepository,
            )
        }
    }

    val transactionsViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            TransactionsViewModel(
                getMonthlyTransactions = getMonthlyTransactionsUseCase,
                transactionRepository = transactionRepository,
                categoryRepository = categoryRepository,
            )
        }
    }

    val budgetViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            BudgetViewModel(
                getBudgetStatus = getBudgetStatusUseCase,
                getMonthlyTransactions = getMonthlyTransactionsUseCase,
                budgetRepository = budgetRepository,
                categoryRepository = categoryRepository,
            )
        }
    }

    val dashboardViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            DashboardViewModel(
                getMonthlySummary = getMonthlySummaryUseCase,
                getBudgetStatus = getBudgetStatusUseCase,
                calculateDailyAvailableBudget = calculateDailyAvailableBudgetUseCase,
                calculateDailyExpense = calculateDailyExpenseUseCase,
                getMonthlyTransactions = getMonthlyTransactionsUseCase,
                getExpenseTrend = getExpenseTrendUseCase,
                budgetRepository = budgetRepository,
                categoryRepository = categoryRepository,
            )
        }
    }

    val dataTransferViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            DataTransferViewModel(
                service = financeDataTransferService,
                repository = localFinanceDataRepository,
                documentStore = documentStore,
                loadCategories = { categoryRepository.getAll() },
            )
        }
    }

    val lanShareViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            LanShareViewModel(bridge = FinanceDataBridge.get(context))
        }
    }
}

package com.financeos.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.financeos.app.ui.viewmodel.AddTransactionViewModel
import com.financeos.app.ui.viewmodel.BudgetViewModel
import com.financeos.app.ui.viewmodel.DashboardViewModel
import com.financeos.app.ui.viewmodel.TransactionsViewModel
import com.financeos.shared.data.local.createFinanceOsDatabase
import com.financeos.shared.data.local.repository.LocalBudgetRepository
import com.financeos.shared.data.local.repository.LocalCategoryRepository
import com.financeos.shared.data.local.repository.LocalTransactionRepository
import com.financeos.shared.domain.usecase.AddTransactionUseCase
import com.financeos.shared.domain.usecase.CalculateDailyAvailableBudgetUseCase
import com.financeos.shared.domain.usecase.CalculateDailyExpenseUseCase
import com.financeos.shared.domain.usecase.GetBudgetStatusUseCase
import com.financeos.shared.domain.usecase.GetMonthlySummaryUseCase
import com.financeos.shared.domain.usecase.GetMonthlyTransactionsUseCase

/** 持有与应用进程同生命周期的本地数据依赖。 */
class FinanceOsApplication : Application() {
    internal val container: FinanceOsAppContainer by lazy {
        FinanceOsAppContainer(applicationContext)
    }
}

/**
 * FinanceOS v0.1 的最小依赖容器。
 *
 * 当前依赖数量仍然很少，手动组装能让依赖方向清晰，也避免仅为少量对象引入 DI 框架。
 */
internal class FinanceOsAppContainer(context: Context) {
    private val database = createFinanceOsDatabase(context)
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
                budgetRepository = budgetRepository,
                categoryRepository = categoryRepository,
            )
        }
    }
}

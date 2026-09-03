package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.repository.BudgetRepository
import com.financeos.shared.domain.repository.CategoryRepository
import com.financeos.shared.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

internal class FakeTransactionRepository(
    initialTransactions: List<Transaction> = emptyList(),
) : TransactionRepository {
    val transactions = initialTransactions.toMutableList()
    private val transactionUpdates = MutableStateFlow(transactions.toList())

    override suspend fun add(transaction: Transaction) {
        transactions += transaction
        transactionUpdates.value = transactions.toList()
    }

    override suspend fun delete(id: String): Boolean {
        val deleted = transactions.removeAll { it.id == id }
        if (deleted) transactionUpdates.value = transactions.toList()
        return deleted
    }

    override suspend fun get(id: String): Transaction? =
        transactions.firstOrNull { it.id == id }

    override suspend fun getAll(): List<Transaction> = transactions.toList()

    override fun observeAll(): Flow<List<Transaction>> = transactionUpdates

    override suspend fun getByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Transaction> = transactions.filter {
        it.dateTime >= startInclusive && it.dateTime < endExclusive
    }

    override fun observeByPeriod(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> = transactionUpdates.map { current ->
        current.filter { it.dateTime >= startInclusive && it.dateTime < endExclusive }
    }

    override suspend fun getByMonth(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Transaction> = getByPeriod(startInclusive, endExclusive)

    override fun observeByMonth(
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<Transaction>> = observeByPeriod(startInclusive, endExclusive)
}

internal class FakeCategoryRepository(
    categories: List<Category>,
) : CategoryRepository {
    private val categoriesById = categories.associateBy(Category::id)
    private val categoryUpdates = MutableStateFlow(categoriesById.values.toList())

    override suspend fun get(id: String): Category? = categoriesById[id]

    override suspend fun getAll(): List<Category> = categoriesById.values.toList()

    override fun observeAll(): Flow<List<Category>> = categoryUpdates
}

internal class FakeBudgetRepository(
    initialBudgets: List<Budget> = emptyList(),
) : BudgetRepository {
    private val budgets = initialBudgets.toMutableList()
    private val budgetUpdates = MutableStateFlow(budgets.toList())

    override suspend fun get(
        month: BudgetMonth,
        categoryId: String?,
    ): Budget? = budgets.firstOrNull {
        it.month == month && it.categoryId == categoryId
    }

    override suspend fun getByMonth(month: BudgetMonth): List<Budget> =
        budgets.filter { it.month == month }

    override fun observeAll(): Flow<List<Budget>> = budgetUpdates

    override fun observeByMonth(month: BudgetMonth): Flow<List<Budget>> =
        budgetUpdates.map { current -> current.filter { it.month == month } }

    override suspend fun save(budget: Budget) {
        budgets.removeAll {
            it.month == budget.month && it.categoryId == budget.categoryId
        }
        budgets += budget
        budgetUpdates.value = budgets.toList()
    }
}

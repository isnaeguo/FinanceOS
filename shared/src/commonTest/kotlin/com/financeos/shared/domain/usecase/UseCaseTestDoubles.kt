package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.repository.BudgetRepository
import com.financeos.shared.domain.repository.CategoryRepository
import com.financeos.shared.domain.repository.TransactionRepository
import kotlin.time.Instant

internal class FakeTransactionRepository(
    initialTransactions: List<Transaction> = emptyList(),
) : TransactionRepository {
    val transactions = initialTransactions.toMutableList()

    override suspend fun add(transaction: Transaction) {
        transactions += transaction
    }

    override suspend fun delete(id: String): Boolean =
        transactions.removeAll { it.id == id }

    override suspend fun get(id: String): Transaction? =
        transactions.firstOrNull { it.id == id }

    override suspend fun getAll(): List<Transaction> = transactions.toList()

    override suspend fun getByMonth(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<Transaction> = transactions.filter {
        it.dateTime >= startInclusive && it.dateTime < endExclusive
    }
}

internal class FakeCategoryRepository(
    categories: List<Category>,
) : CategoryRepository {
    private val categoriesById = categories.associateBy(Category::id)

    override suspend fun get(id: String): Category? = categoriesById[id]

    override suspend fun getAll(): List<Category> = categoriesById.values.toList()
}

internal class FakeBudgetRepository(
    initialBudgets: List<Budget> = emptyList(),
) : BudgetRepository {
    private val budgets = initialBudgets.toMutableList()

    override suspend fun get(
        month: BudgetMonth,
        categoryId: String?,
    ): Budget? = budgets.firstOrNull {
        it.month == month && it.categoryId == categoryId
    }

    override suspend fun getByMonth(month: BudgetMonth): List<Budget> =
        budgets.filter { it.month == month }

    override suspend fun save(budget: Budget) {
        budgets.removeAll {
            it.month == budget.month && it.categoryId == budget.categoryId
        }
        budgets += budget
    }
}

package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.Category
import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.TransactionType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class AddTransactionUseCaseTest {
    @Test
    fun validatesAndSavesTransaction() = runTest {
        val transactionRepository = FakeTransactionRepository()
        val useCase = AddTransactionUseCase(
            transactionRepository = transactionRepository,
            categoryRepository = FakeCategoryRepository(listOf(category())),
        )

        val saved = useCase(command())

        assertEquals(1, transactionRepository.transactions.size)
        assertEquals(saved, transactionRepository.transactions.single())
        assertEquals(1_299L, saved.amount)
    }

    @Test
    fun rejectsNonPositiveAmountBeforeSaving() = runTest {
        val transactionRepository = FakeTransactionRepository()
        val useCase = AddTransactionUseCase(
            transactionRepository = transactionRepository,
            categoryRepository = FakeCategoryRepository(listOf(category())),
        )

        assertFailsWith<IllegalArgumentException> {
            useCase(command(amount = 0L))
        }
        assertFailsWith<IllegalArgumentException> {
            useCase(command(amount = -1L))
        }
        assertEquals(emptyList(), transactionRepository.transactions)
    }

    @Test
    fun rejectsMissingOrIncompatibleCategory() = runTest {
        val transactionRepository = FakeTransactionRepository()
        val missingCategoryUseCase = AddTransactionUseCase(
            transactionRepository = transactionRepository,
            categoryRepository = FakeCategoryRepository(emptyList()),
        )
        val incomeCategoryUseCase = AddTransactionUseCase(
            transactionRepository = transactionRepository,
            categoryRepository = FakeCategoryRepository(
                listOf(category(type = CategoryType.INCOME)),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            missingCategoryUseCase(command())
        }
        assertFailsWith<IllegalArgumentException> {
            incomeCategoryUseCase(command(type = TransactionType.EXPENSE))
        }
        assertEquals(emptyList(), transactionRepository.transactions)
    }

    private fun command(
        amount: Long = 1_299L,
        type: TransactionType = TransactionType.EXPENSE,
    ) = AddTransactionCommand(
        id = "transaction-id",
        amount = amount,
        type = type,
        categoryId = "food",
        dateTime = Instant.parse("2026-08-10T08:00:00Z"),
    )

    private fun category(
        type: CategoryType = CategoryType.EXPENSE,
    ) = Category(
        id = "food",
        name = "餐饮",
        type = type,
        iconKey = "food",
        isSystem = true,
    )
}

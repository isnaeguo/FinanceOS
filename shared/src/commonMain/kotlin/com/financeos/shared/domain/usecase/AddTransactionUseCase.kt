package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.CategoryType
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import com.financeos.shared.domain.repository.CategoryRepository
import com.financeos.shared.domain.repository.TransactionRepository
import kotlin.time.Instant

/** 新增流水所需的未经持久化输入。 */
data class AddTransactionCommand(
    val id: String,
    val amount: Long,
    val type: TransactionType,
    val categoryId: String,
    val accountId: String? = null,
    val dateTime: Instant,
    val note: String? = null,
)

/** 校验金额与分类后保存一笔流水。 */
class AddTransactionUseCase(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
) {
    suspend operator fun invoke(command: AddTransactionCommand): Transaction {
        require(command.amount > 0) { "Transaction amount must be greater than zero." }
        require(command.categoryId.isNotBlank()) { "Transaction categoryId must not be blank." }

        val category = requireNotNull(categoryRepository.get(command.categoryId)) {
            "Transaction category does not exist."
        }
        require(category.type.accepts(command.type)) {
            "Transaction type is not supported by the selected category."
        }

        val transaction = Transaction(
            id = command.id,
            amount = command.amount,
            type = command.type,
            categoryId = command.categoryId,
            accountId = command.accountId,
            dateTime = command.dateTime,
            note = command.note,
        )
        transactionRepository.add(transaction)
        return transaction
    }
}

private fun CategoryType.accepts(transactionType: TransactionType): Boolean = when (this) {
    CategoryType.COMMON -> true
    CategoryType.INCOME -> transactionType == TransactionType.INCOME
    CategoryType.EXPENSE -> transactionType == TransactionType.EXPENSE
}

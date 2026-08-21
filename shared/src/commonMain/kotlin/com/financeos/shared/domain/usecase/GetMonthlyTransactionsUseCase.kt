package com.financeos.shared.domain.usecase

import com.financeos.shared.domain.model.MonthPeriod
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow

/** 获取用户时区中指定月份的全部流水。 */
class GetMonthlyTransactionsUseCase(
    private val transactionRepository: TransactionRepository,
) {
    suspend operator fun invoke(period: MonthPeriod): List<Transaction> =
        transactionRepository.getByMonth(
            startInclusive = period.startInclusive,
            endExclusive = period.endExclusive,
        )

    /** 持续读取指定月份，供需要响应增删变化的界面使用。 */
    fun observe(period: MonthPeriod): Flow<List<Transaction>> =
        transactionRepository.observeByMonth(
            startInclusive = period.startInclusive,
            endExclusive = period.endExclusive,
        )
}

package com.financeos.shared.domain.repository

import com.financeos.shared.domain.model.Budget
import com.financeos.shared.domain.model.BudgetMonth
import kotlinx.coroutines.flow.Flow

/** 定义月总预算和分类月预算所需的最小存取能力。 */
interface BudgetRepository {
    /** 获取指定月份和分类的预算；[categoryId] 为 `null` 时获取月总预算。 */
    suspend fun get(
        month: BudgetMonth,
        categoryId: String? = null,
    ): Budget?

    /** 获取指定月份的全部预算。 */
    suspend fun getByMonth(month: BudgetMonth): List<Budget>

    /** 持续观察全部预算，使适配层等长期订阅方能与数据库保持一致。 */
    fun observeAll(): Flow<List<Budget>>

    /** 持续观察指定月份预算，使 Dashboard 能响应预算新增或修改。 */
    fun observeByMonth(month: BudgetMonth): Flow<List<Budget>>

    /** 按预算 ID 保存新预算或更新已有预算。 */
    suspend fun save(budget: Budget)
}

package com.financeos.shared.domain.calculation

import com.financeos.shared.domain.model.Budget

/**
 * 一次纯业务预算计算的结果。
 *
 * 没有设置预算时，[amountRemaining] 和 [usageRatio] 为 `null`，调用方可通过 [hasBudget] 明确区分。
 * [usageRatio] 是无量纲比例，不用于保存或计算货币金额，因此可以安全使用 [Double]。
 *
 * [amountUsed] 允许为负：月总预算按“净支出 = 支出 − 收入”统计，收入大于支出时为负，
 * 表示当月有结余、不会超支。分类预算仍按分类支出统计（恒为非负）。
 */
data class BudgetUsage(
    val amountLimit: Long?,
    val amountUsed: Long,
    val amountRemaining: Long?,
    val usageRatio: Double?,
    val isOverBudget: Boolean,
    val hasBudget: Boolean,
)

/** 计算预算的已使用金额、剩余金额、使用比例和超支状态。 */
object BudgetCalculator {
    fun calculate(
        budget: Budget?,
        amountUsed: Long,
    ): BudgetUsage {
        if (budget == null) {
            // 没有预算就不存在可比较的额度，因此不虚构剩余金额、比例或超支状态。
            return BudgetUsage(
                amountLimit = null,
                amountUsed = amountUsed,
                amountRemaining = null,
                usageRatio = null,
                isOverBudget = false,
                hasBudget = false,
            )
        }

        val usageRatio = if (budget.amountLimit == 0L) {
            // 零额度下比例在数学上没有定义，返回 null 可避免 NaN 或 Infinity 进入业务层。
            null
        } else {
            amountUsed.toDouble() / budget.amountLimit.toDouble()
        }

        return BudgetUsage(
            amountLimit = budget.amountLimit,
            amountUsed = amountUsed,
            amountRemaining = budget.amountLimit - amountUsed,
            usageRatio = usageRatio,
            isOverBudget = amountUsed > budget.amountLimit,
            hasBudget = true,
        )
    }
}

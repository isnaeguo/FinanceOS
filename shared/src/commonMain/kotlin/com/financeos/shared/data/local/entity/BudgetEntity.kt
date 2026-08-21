package com.financeos.shared.data.local.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Room 中的月预算存储结构。 */
@Entity(
    tableName = "budgets",
    indices = [
        Index(
            value = ["year", "month", "category_key"],
            unique = true,
        ),
    ],
)
data class BudgetEntity(
    @PrimaryKey
    val id: String,
    val year: Int,
    val month: Int,
    @ColumnInfo(name = "category_key")
    val categoryKey: String,
    @ColumnInfo(name = "amount_limit_minor")
    val amountLimitMinor: Long,
)

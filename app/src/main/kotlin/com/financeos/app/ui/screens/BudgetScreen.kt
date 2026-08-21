package com.financeos.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.financeos.app.ui.components.SkeletonSection

/** 月总预算和分类预算的页面框架。 */
@Composable
internal fun BudgetScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SkeletonSection(
                title = "月总预算",
                description = "尚未设置本月总预算",
            )
        }
        item {
            SkeletonSection(
                title = "分类预算",
                description = "尚未设置分类预算",
            )
        }
    }
}

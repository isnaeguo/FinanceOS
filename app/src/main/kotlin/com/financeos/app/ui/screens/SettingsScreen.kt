package com.financeos.app.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 设置页框架，不提前加入登录、同步等 v0.1 范围外功能。 */
@Composable
internal fun SettingsScreen() {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text("货币与金额") },
                supportingContent = { Text("金额显示设置将在后续阶段接入") },
            )
        }
        item { HorizontalDivider() }
        item {
            ListItem(
                headlineContent = { Text("数据与隐私") },
                supportingContent = { Text("FinanceOS v0.1 数据仅保存在本机") },
            )
        }
        item { HorizontalDivider() }
        item {
            ListItem(
                headlineContent = { Text("关于 FinanceOS") },
                supportingContent = {
                    Text(
                        text = "版本 0.1.0",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

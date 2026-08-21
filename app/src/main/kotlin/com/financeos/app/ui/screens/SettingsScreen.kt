package com.financeos.app.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 设置页仅展示 v0.1 当前有效的数据说明和版本信息。 */
@Composable
internal fun SettingsScreen() {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                        text = "版本 0.1.0 · isnaeguo",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

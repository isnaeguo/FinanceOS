package com.financeos.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.financeos.app.ui.components.SkeletonSection

/** 首页框架；真实月汇总和预算状态将在 ViewModel 接入后替换占位内容。 */
@Composable
internal fun HomeScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "掌握这个月的每一笔钱",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item {
            Text(
                text = "开始记账后，这里会显示本月收支与预算概况。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            SkeletonSection(
                title = "本月概览",
                description = "暂无可展示的收支数据",
            )
        }
        item {
            SkeletonSection(
                title = "预算状态",
                description = "设置月预算后可查看使用情况和每日可用预算",
            )
        }
    }
}

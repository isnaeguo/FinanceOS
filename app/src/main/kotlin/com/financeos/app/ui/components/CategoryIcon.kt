package com.financeos.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector

/** 将跨平台 iconKey 映射为 Android Material 图标，不让 Domain 保存 Drawable ID。 */
internal fun categoryIcon(iconKey: String): ImageVector = when (iconKey) {
    "food" -> Icons.Default.Favorite
    "transport" -> Icons.Default.Place
    "shopping" -> Icons.Default.ShoppingCart
    "entertainment" -> Icons.Default.PlayArrow
    "digital" -> Icons.Default.Phone
    "learning" -> Icons.Default.Create
    "travel" -> Icons.Default.Place
    "daily-needs" -> Icons.Default.Home
    "income" -> Icons.Default.AddCircle
    else -> Icons.Default.MoreVert
}

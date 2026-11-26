package com.trackr.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * v3 圆角：small 8（线路号、chip）/ medium 16（卡片）/ extraLarge 28（底部弹层）。
 * 按钮和胶囊全圆，用 M3 组件默认的 CircleShape / ShapeDefaults.Full。
 */
internal val StandardShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

package com.trackr.core.designsystem.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.theme.DataSourceChelaileContainer
import com.trackr.core.designsystem.theme.DataSourceChelaileContent
import com.trackr.core.designsystem.theme.DataSourceMygolbsContainer
import com.trackr.core.designsystem.theme.DataSourceMygolbsContent

/**
 * 数据源样式预设
 */
enum class DataSourceBadgeStyle(
    val defaultText: String,
    val containerColor: Color,
    val contentColor: Color
) {
    CHELAILE("车来了", DataSourceChelaileContainer, DataSourceChelaileContent),
    MYGOLBS("掌上公交", DataSourceMygolbsContainer, DataSourceMygolbsContent)
}

/**
 * 统一的数据源徽标组件（设计系统底座组件）
 */
@Composable
fun DataSourceBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    fontSize: TextUnit = 10.sp,
    fontWeight: FontWeight = FontWeight.Medium,
    contentPadding: PaddingValues = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
) {
    Surface(
        shape = shape,
        color = containerColor,
        modifier = modifier
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = contentColor,
            modifier = Modifier.padding(contentPadding)
        )
    }
}

/**
 * 样式预设重载
 */
@Composable
fun DataSourceBadge(
    style: DataSourceBadgeStyle,
    modifier: Modifier = Modifier,
    text: String = style.defaultText,
    shape: Shape = RoundedCornerShape(6.dp),
    fontSize: TextUnit = 10.sp,
    fontWeight: FontWeight = FontWeight.Medium,
    contentPadding: PaddingValues = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
) {
    DataSourceBadge(
        text = text,
        containerColor = style.containerColor,
        contentColor = style.contentColor,
        modifier = modifier,
        shape = shape,
        fontSize = fontSize,
        fontWeight = fontWeight,
        contentPadding = contentPadding
    )
}

package com.trackr.ui.components.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.component.DataSourceBadgeStyle
import com.trackr.core.designsystem.component.DataSourceBadge as CoreDataSourceBadge
import com.trackr.domain.model.DataSourceType

/**
 * 统一的数据源（车来了 / 掌上公交）徽标组件适配器
 */
@Composable
fun DataSourceBadge(
    source: DataSourceType,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    fontSize: TextUnit = 10.sp,
    fontWeight: FontWeight = FontWeight.Medium,
    contentPadding: PaddingValues = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
) {
    val style = when (source) {
        DataSourceType.CHELAILE -> DataSourceBadgeStyle.CHELAILE
        DataSourceType.MYGOLBS -> DataSourceBadgeStyle.MYGOLBS
    }
    CoreDataSourceBadge(
        style = style,
        text = source.displayName,
        modifier = modifier,
        shape = shape,
        fontSize = fontSize,
        fontWeight = fontWeight,
        contentPadding = contentPadding
    )
}

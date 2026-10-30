package com.trackr.ui.components.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.domain.model.DataSourceType
import com.trackr.ui.theme.DataSourceChelaileContainer
import com.trackr.ui.theme.DataSourceChelaileContent
import com.trackr.ui.theme.DataSourceMygolbsContainer
import com.trackr.ui.theme.DataSourceMygolbsContent

/**
 * 统一的数据源（车来了 / 掌上公交）徽标组件
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
    val (containerColor, contentColor) = when (source) {
        DataSourceType.CHELAILE -> DataSourceChelaileContainer to DataSourceChelaileContent
        DataSourceType.MYGOLBS -> DataSourceMygolbsContainer to DataSourceMygolbsContent
    }

    Surface(
        shape = shape,
        color = containerColor,
        modifier = modifier
    ) {
        Text(
            text = source.displayName,
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = contentColor,
            modifier = Modifier.padding(contentPadding)
        )
    }
}

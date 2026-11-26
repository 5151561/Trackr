package com.trackr.ui.screens.linedetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.component.TrackrBottomSheet
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.group_none
import com.trackr.core.designsystem.resources.ic_check
import com.trackr.core.model.FavoriteGroup
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_group_current
import com.trackr.feature.line.resources.line_group_pick_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 「换分组」弹层：收藏后提示条上的按钮打开。列出「不分组」和全部收藏分组，当前所在的打勾。
 * 分组的增删改在编辑收藏页，这里只选。
 *
 * @param onPick 选中的分组 id；null = 不分组
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteGroupPickerSheet(
    groups: List<FavoriteGroup>,
    currentGroupId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    TrackrBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(LineRes.string.line_group_pick_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        GroupRow(name = stringResource(Res.string.group_none), selected = currentGroupId == null, onClick = { onPick(null) })
        groups.forEach { group ->
            GroupRow(name = group.name, selected = currentGroupId == group.id, onClick = { onPick(group.id) })
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun GroupRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_check),
                contentDescription = stringResource(LineRes.string.line_group_current),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

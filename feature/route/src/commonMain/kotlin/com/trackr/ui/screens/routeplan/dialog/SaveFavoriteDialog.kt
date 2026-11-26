package com.trackr.ui.screens.routeplan.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_add
import com.trackr.core.designsystem.resources.action_cancel
import com.trackr.core.designsystem.resources.ic_plus
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.FavoriteGroup
import com.trackr.feature.route.resources.Res as RouteRes
import com.trackr.feature.route.resources.route_detail_name
import com.trackr.feature.route.resources.route_save_all
import com.trackr.feature.route.resources.route_save_confirm
import com.trackr.feature.route.resources.route_save_new_group
import com.trackr.feature.route.resources.route_save_new_group_hint
import com.trackr.feature.route.resources.route_save_pick_group
import com.trackr.feature.route.resources.route_save_scheme
import com.trackr.feature.route.resources.route_save_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 存进收藏：路线名 + 分组（「全部」= 不指定，回落到收藏页当前选中的分组）+ 新建分组。
 * 第 24 步从旧页面原样搬进 commonMain；v3 的收藏弹层（只问名字和分组）是第 22 步。
 */
@Composable
internal fun SaveFavoriteDialog(
    scheme: AmapTransitSchemeResult,
    favoriteGroups: List<FavoriteGroup>,
    onDismiss: () -> Unit,
    onConfirmSave: (groupId: String?, name: String) -> Unit,
    onCreateNewGroup: (String) -> Unit
) {
    var groupName by remember(scheme) { mutableStateOf(scheme.group.name) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var showNewGroupInput by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(RouteRes.string.route_save_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(RouteRes.string.route_save_scheme, scheme.title, scheme.durationMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(RouteRes.string.route_detail_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_favorite_name")
                )

                Text(
                    text = stringResource(RouteRes.string.route_save_pick_group),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedGroupId == null,
                        onClick = { selectedGroupId = null },
                        label = { Text(stringResource(RouteRes.string.route_save_all)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    favoriteGroups.forEach { group ->
                        FilterChip(
                            selected = selectedGroupId == group.id,
                            onClick = { selectedGroupId = group.id },
                            label = { Text(group.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }

                if (!showNewGroupInput) {
                    TextButton(
                        onClick = { showNewGroupInput = true },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(imageVector = vectorResource(Res.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(RouteRes.string.route_save_new_group))
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = newGroupName,
                            onValueChange = { newGroupName = it },
                            placeholder = { Text(stringResource(RouteRes.string.route_save_new_group_hint)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (newGroupName.isNotBlank()) {
                                    onCreateNewGroup(newGroupName.trim())
                                    newGroupName = ""
                                    showNewGroupInput = false
                                }
                            }
                        ) {
                            Text(stringResource(Res.string.action_add))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmSave(selectedGroupId, groupName.trim()) },
                modifier = Modifier.testTag("btn_confirm_save_favorite")
            ) {
                Text(stringResource(RouteRes.string.route_save_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}

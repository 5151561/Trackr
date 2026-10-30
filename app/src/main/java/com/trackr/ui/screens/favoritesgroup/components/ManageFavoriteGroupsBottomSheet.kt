package com.trackr.ui.screens.favoritesgroup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.trackr.domain.model.FavoriteGroup

/**
 * 收藏中心分组管理弹窗 / 抽屉
 */
@Composable
fun ManageFavoriteGroupsBottomSheet(
    favoriteGroups: List<FavoriteGroup>,
    onDismiss: () -> Unit,
    onCreateGroup: (name: String) -> Unit,
    onRenameGroup: (groupId: String, newName: String) -> Unit,
    onDeleteGroup: (groupId: String) -> Unit,
    onReorderGroups: (groupIds: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var newGroupName by remember { mutableStateOf("") }
    var renamingGroup by remember { mutableStateOf<FavoriteGroup?>(null) }
    var renameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = "管理收藏分组",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "仅删除分组标签，组内收藏将归入全部",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 新建分组输入栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text("新建分组") },
                        placeholder = { Text("例如：健身房、商圈购物") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_new_favorite_group"),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newGroupName.isNotBlank()) {
                                onCreateGroup(newGroupName.trim())
                                newGroupName = ""
                            }
                        },
                        enabled = newGroupName.isNotBlank(),
                        modifier = Modifier.testTag("btn_create_favorite_group")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("新建")
                    }
                }

                // 分组列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(favoriteGroups, key = { _, item -> item.id }) { index, group ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("item_favorite_group_${group.id}"),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )

                                // 上移
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val mutable = favoriteGroups.map { it.id }.toMutableList()
                                            val temp = mutable[index]
                                            mutable[index] = mutable[index - 1]
                                            mutable[index - 1] = temp
                                            onReorderGroups(mutable)
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "上移",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // 下移
                                IconButton(
                                    onClick = {
                                        if (index < favoriteGroups.size - 1) {
                                            val mutable = favoriteGroups.map { it.id }.toMutableList()
                                            val temp = mutable[index]
                                            mutable[index] = mutable[index + 1]
                                            mutable[index + 1] = temp
                                            onReorderGroups(mutable)
                                        }
                                    },
                                    enabled = index < favoriteGroups.size - 1,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "下移",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // 重命名
                                IconButton(
                                    onClick = {
                                        renamingGroup = group
                                        renameText = group.name
                                    },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("btn_rename_favorite_group_${group.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "重命名",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // 删除
                                IconButton(
                                    onClick = { onDeleteGroup(group.id) },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("btn_delete_favorite_group_${group.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "删除分组",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("btn_dismiss_manage_groups")
            ) {
                Text("完成")
            }
        }
    )

    // 重命名对话框
    if (renamingGroup != null) {
        val target = renamingGroup!!
        AlertDialog(
            onDismissRequest = { renamingGroup = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            onRenameGroup(target.id, renameText.trim())
                            renamingGroup = null
                        }
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingGroup = null }) {
                    Text("取消")
                }
            }
        )
    }
}

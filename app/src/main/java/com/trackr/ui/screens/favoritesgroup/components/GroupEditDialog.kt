package com.trackr.ui.screens.favoritesgroup.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.domain.model.CandidateLine
import com.trackr.domain.model.GroupCategory
import com.trackr.domain.model.TransitGroup
import com.trackr.domain.model.TransitLeg
import java.util.UUID

/**
 * 创建/编辑路线组弹窗
 */
@Composable
fun GroupEditDialog(
    initialGroup: TransitGroup?,
    onDismiss: () -> Unit,
    onSave: (TransitGroup) -> Unit
) {
    var groupName by remember { mutableStateOf(initialGroup?.name.orEmpty()) }
    var selectedCategory by remember { mutableStateOf(initialGroup?.category ?: GroupCategory.COMMUTE) }

    // 简易两段换乘站名与路线名输入
    val leg1 = initialGroup?.legs?.getOrNull(0)
    val leg2 = initialGroup?.legs?.getOrNull(1)

    var from1 by remember { mutableStateOf(leg1?.fromStopName.orEmpty()) }
    var to1 by remember { mutableStateOf(leg1?.toStopName.orEmpty()) }
    var line1 by remember { mutableStateOf(leg1?.primaryLine?.lineName.orEmpty()) }

    var from2 by remember { mutableStateOf(leg2?.fromStopName.orEmpty().ifEmpty { to1 }) }
    var to2 by remember { mutableStateOf(leg2?.toStopName.orEmpty()) }
    var line2 by remember { mutableStateOf(leg2?.primaryLine?.lineName.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialGroup == null) "新建路线分组" else "编辑路线分组") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("分组名称") },
                    placeholder = { Text("例如：早晚高峰上下班通勤") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text("所属场景", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GroupCategory.entries.filter { it != GroupCategory.TEMPORARY }.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.displayName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("第 1 程路线", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = from1,
                        onValueChange = {
                            from1 = it
                            if (from2.isEmpty()) from2 = to1
                        },
                        label = { Text("起点站") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = to1,
                        onValueChange = {
                            to1 = it
                            from2 = it // 自动联动第二程起点
                        },
                        label = { Text("换乘站") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = line1,
                    onValueChange = { line1 = it },
                    label = { Text("乘坐线路（如 156路）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("第 2 程路线（可选）", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = from2,
                        onValueChange = { from2 = it },
                        label = { Text("换乘站") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = to2,
                        onValueChange = { to2 = it },
                        label = { Text("终点站") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = line2,
                    onValueChange = { line2 = it },
                    label = { Text("换乘线路（如 地铁4号线）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = groupName.trim().ifEmpty { "${from1} ➔ ${to2.ifEmpty { to1 }}" }
                    val legs = mutableListOf<TransitLeg>()
                    if (from1.isNotBlank() && to1.isNotBlank()) {
                        legs.add(
                            TransitLeg(
                                legIndex = 0,
                                fromStopName = from1.trim(),
                                toStopName = to1.trim(),
                                candidateLines = listOf(
                                    CandidateLine(
                                        cityId = leg1?.primaryLine?.cityId ?: "033",
                                        cityName = leg1?.primaryLine?.cityName ?: "杭州",
                                        lineId = leg1?.primaryLine?.lineId ?: "line_1_${UUID.randomUUID().toString().take(6)}",
                                        lineName = line1.trim().ifEmpty { "156路" },
                                        fromStopOrder = 1,
                                        toStopOrder = 5
                                    )
                                )
                            )
                        )
                    }
                    if (from2.isNotBlank() && to2.isNotBlank()) {
                        legs.add(
                            TransitLeg(
                                legIndex = 1,
                                fromStopName = from2.trim(),
                                toStopName = to2.trim(),
                                candidateLines = listOf(
                                    CandidateLine(
                                        cityId = leg2?.primaryLine?.cityId ?: "033",
                                        cityName = leg2?.primaryLine?.cityName ?: "杭州",
                                        lineId = leg2?.primaryLine?.lineId ?: "line_2_${UUID.randomUUID().toString().take(6)}",
                                        lineName = line2.trim().ifEmpty { "地铁4号线" },
                                        fromStopOrder = 1,
                                        toStopOrder = 6
                                    )
                                ),
                                walkingDistanceMeters = 60
                            )
                        )
                    }

                    val updated = initialGroup?.copy(
                        name = finalName,
                        category = selectedCategory,
                        legs = if (legs.isNotEmpty()) legs else initialGroup.legs,
                        updatedAt = System.currentTimeMillis()
                    ) ?: TransitGroup(
                        id = UUID.randomUUID().toString(),
                        name = finalName,
                        category = selectedCategory,
                        legs = legs
                    )
                    onSave(updated)
                },
                enabled = from1.isNotBlank() && to1.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

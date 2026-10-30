package com.trackr.ui.screens.favoritesgroup.components

import androidx.activity.compose.BackHandler
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
import com.trackr.domain.model.FavoriteGroup
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
    favoriteGroups: List<FavoriteGroup> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (TransitGroup) -> Unit
) {
    val leg1 = initialGroup?.legs?.getOrNull(0)
    val leg2 = initialGroup?.legs?.getOrNull(1)

    val initialName = remember(initialGroup) { initialGroup?.name.orEmpty() }
    val initialCategory = remember(initialGroup) { initialGroup?.category ?: GroupCategory.COMMUTE }
    val initialCustomGroupId = remember(initialGroup) { initialGroup?.customGroupId }

    val initialFrom1 = remember(leg1) { leg1?.fromStopName.orEmpty() }
    val initialTo1 = remember(leg1) { leg1?.toStopName.orEmpty() }
    val initialLine1 = remember(leg1) {
        val names = leg1?.candidateLines?.map { it.lineName }?.filter { it.isNotBlank() }.orEmpty()
        if (names.isNotEmpty()) names.joinToString(", ") else leg1?.primaryLine?.lineName.orEmpty()
    }

    val initialFrom2 = remember(leg2, leg1) { leg2?.fromStopName.orEmpty().ifEmpty { initialTo1 } }
    val initialTo2 = remember(leg2) { leg2?.toStopName.orEmpty() }
    val initialLine2 = remember(leg2) {
        val names = leg2?.candidateLines?.map { it.lineName }?.filter { it.isNotBlank() }.orEmpty()
        if (names.isNotEmpty()) names.joinToString(", ") else leg2?.primaryLine?.lineName.orEmpty()
    }

    var groupName by remember { mutableStateOf(initialName) }
    var selectedCategory by remember { mutableStateOf(initialCategory) }
    var selectedCustomGroupId by remember { mutableStateOf(initialCustomGroupId) }

    var from1 by remember { mutableStateOf(initialFrom1) }
    var to1 by remember { mutableStateOf(initialTo1) }
    var line1 by remember { mutableStateOf(initialLine1) }

    var from2 by remember { mutableStateOf(initialFrom2) }
    var to2 by remember { mutableStateOf(initialTo2) }
    var line2 by remember { mutableStateOf(initialLine2) }

    var showDiscardConfirmDialog by remember { mutableStateOf(false) }

    val isDirty = groupName != initialName ||
        selectedCategory != initialCategory ||
        selectedCustomGroupId != initialCustomGroupId ||
        from1 != initialFrom1 ||
        to1 != initialTo1 ||
        line1 != initialLine1 ||
        from2 != initialFrom2 ||
        to2 != initialTo2 ||
        line2 != initialLine2

    // 拦截物理返回键或手势返回，防止误触导致表单内容丢失
    BackHandler(enabled = isDirty) {
        showDiscardConfirmDialog = true
    }

    val handleDismissRequest = {
        if (isDirty) {
            showDiscardConfirmDialog = true
        } else {
            onDismiss()
        }
    }

    fun parseLineNames(raw: String): List<String> {
        return raw.split(Regex("[,，、\\s]+")).map { it.trim() }.filter { it.isNotBlank() }
    }

    AlertDialog(
        onDismissRequest = handleDismissRequest,
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

                // 所属收藏分组选择
                if (favoriteGroups.isNotEmpty()) {
                    Text("所属收藏分组", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedCustomGroupId == null,
                            onClick = { selectedCustomGroupId = null },
                            label = { Text("全部 (未特别归组)") }
                        )
                        favoriteGroups.forEach { fg ->
                            FilterChip(
                                selected = selectedCustomGroupId == fg.id,
                                onClick = {
                                    selectedCustomGroupId = fg.id
                                    if (fg.id == "commute") {
                                        selectedCategory = GroupCategory.COMMUTE
                                    }
                                },
                                label = { Text(fg.name) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

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
                    placeholder = { Text("支持多线路用逗号隔开，如 320, M194, 311") },
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
                    placeholder = { Text("支持多线路用逗号隔开，如 19, 204") },
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
                        val parsedLines1 = parseLineNames(line1)
                        val candidates1 = if (parsedLines1.isNotEmpty()) {
                            parsedLines1.mapIndexed { idx, name ->
                                val existing = leg1?.candidateLines?.getOrNull(idx)
                                CandidateLine(
                                    cityId = existing?.cityId ?: leg1?.primaryLine?.cityId ?: "033",
                                    cityName = existing?.cityName ?: leg1?.primaryLine?.cityName ?: "杭州",
                                    lineId = existing?.lineId ?: "line_1_${idx}_${UUID.randomUUID().toString().take(6)}",
                                    lineName = name,
                                    fromStopOrder = existing?.fromStopOrder ?: 1,
                                    toStopOrder = existing?.toStopOrder ?: 5
                                )
                            }
                        } else {
                            listOf(
                                CandidateLine(
                                    cityId = leg1?.primaryLine?.cityId ?: "033",
                                    cityName = leg1?.primaryLine?.cityName ?: "杭州",
                                    lineId = leg1?.primaryLine?.lineId ?: "line_1_${UUID.randomUUID().toString().take(6)}",
                                    lineName = "156路",
                                    fromStopOrder = 1,
                                    toStopOrder = 5
                                )
                            )
                        }

                        legs.add(
                            TransitLeg(
                                legIndex = 0,
                                fromStopName = from1.trim(),
                                toStopName = to1.trim(),
                                candidateLines = candidates1
                            )
                        )
                    }
                    if (from2.isNotBlank() && to2.isNotBlank()) {
                        val parsedLines2 = parseLineNames(line2)
                        val candidates2 = if (parsedLines2.isNotEmpty()) {
                            parsedLines2.mapIndexed { idx, name ->
                                val existing = leg2?.candidateLines?.getOrNull(idx)
                                CandidateLine(
                                    cityId = existing?.cityId ?: leg2?.primaryLine?.cityId ?: "033",
                                    cityName = existing?.cityName ?: leg2?.primaryLine?.cityName ?: "杭州",
                                    lineId = existing?.lineId ?: "line_2_${idx}_${UUID.randomUUID().toString().take(6)}",
                                    lineName = name,
                                    fromStopOrder = existing?.fromStopOrder ?: 1,
                                    toStopOrder = existing?.toStopOrder ?: 6
                                )
                            }
                        } else {
                            listOf(
                                CandidateLine(
                                    cityId = leg2?.primaryLine?.cityId ?: "033",
                                    cityName = leg2?.primaryLine?.cityName ?: "杭州",
                                    lineId = leg2?.primaryLine?.lineId ?: "line_2_${UUID.randomUUID().toString().take(6)}",
                                    lineName = "地铁4号线",
                                    fromStopOrder = 1,
                                    toStopOrder = 6
                                )
                            )
                        }

                        legs.add(
                            TransitLeg(
                                legIndex = 1,
                                fromStopName = from2.trim(),
                                toStopName = to2.trim(),
                                candidateLines = candidates2,
                                walkingDistanceMeters = 60
                            )
                        )
                    }

                    val updated = initialGroup?.copy(
                        name = finalName,
                        category = selectedCategory,
                        customGroupId = selectedCustomGroupId,
                        legs = if (legs.isNotEmpty()) legs else initialGroup.legs,
                        updatedAt = System.currentTimeMillis()
                    ) ?: TransitGroup(
                        id = UUID.randomUUID().toString(),
                        name = finalName,
                        category = selectedCategory,
                        customGroupId = selectedCustomGroupId,
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
            TextButton(onClick = handleDismissRequest) {
                Text("取消")
            }
        }
    )

    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前表单已有编辑内容，退出后所作修改将不会保存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmDialog = false
                        onDismiss()
                    }
                ) {
                    Text("放弃修改", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmDialog = false }) {
                    Text("继续编辑")
                }
            }
        )
    }
}

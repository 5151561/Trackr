package com.trackr.ui.screens.favoritesgroup.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.data.remote.amap.AmapTipItem
import com.trackr.data.remote.amap.AmapTransitSchemeResult

/**
 * 高德智能公交路径规划弹窗
 */
@Composable
fun AmapRoutePlanDialog(
    originTips: List<AmapTipItem>,
    destTips: List<AmapTipItem>,
    plannedSchemes: List<AmapTransitSchemeResult>,
    isPlanning: Boolean,
    onSearchOrigin: (String) -> Unit,
    onSearchDest: (String) -> Unit,
    onPlanRoute: (origin: AmapTipItem, dest: AmapTipItem) -> Unit,
    onAddScheme: (AmapTransitSchemeResult) -> Unit,
    onStartTrackingScheme: (AmapTransitSchemeResult) -> Unit,
    onDismiss: () -> Unit,
    customApiKey: String? = null,
    onSaveCustomApiKey: ((String) -> Unit)? = null,
    onClearOriginTips: () -> Unit = { onSearchOrigin("") },
    onClearDestTips: () -> Unit = { onSearchDest("") }
) {
    var originText by remember { mutableStateOf("") }
    var destText by remember { mutableStateOf("") }
    var selectedOrigin by remember { mutableStateOf<AmapTipItem?>(null) }
    var selectedDest by remember { mutableStateOf<AmapTipItem?>(null) }

    var dismissOriginTips by remember { mutableStateOf(false) }
    var dismissDestTips by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var inputKey by remember(customApiKey) { mutableStateOf(customApiKey.orEmpty()) }

    // 当搜索结果更新时，重新显示候选列表
    LaunchedEffect(originTips) {
        if (originTips.isNotEmpty()) {
            dismissOriginTips = false
        }
    }
    LaunchedEffect(destTips) {
        if (destTips.isNotEmpty()) {
            dismissDestTips = false
        }
    }

    val isDestTipsVisible = !dismissDestTips && selectedDest == null && destTips.isNotEmpty()
    val isOriginTipsVisible = !dismissOriginTips && selectedOrigin == null && originTips.isNotEmpty()
    val hasTipsVisible = isDestTipsVisible || isOriginTipsVisible

    // 遵循 LIFO 消费返回事件：先收起终点候选建议 -> 再收起起点候选建议 -> 最后才关闭对话框
    BackHandler(enabled = hasTipsVisible) {
        if (isDestTipsVisible) {
            dismissDestTips = true
            onClearDestTips()
        } else if (isOriginTipsVisible) {
            dismissOriginTips = true
            onClearOriginTips()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (hasTipsVisible) {
                if (isDestTipsVisible) {
                    dismissDestTips = true
                    onClearDestTips()
                } else if (isOriginTipsVisible) {
                    dismissOriginTips = true
                    onClearOriginTips()
                }
            } else {
                onDismiss()
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.AltRoute,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "智能公交换乘规划",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                if (onSaveCustomApiKey != null) {
                    IconButton(
                        onClick = {
                            inputKey = customApiKey.orEmpty()
                            showKeyDialog = true
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "高德 Web Key 设置",
                            tint = if (customApiKey.isNullOrBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp)
            ) {
                Text(
                    text = "输入起终点一键规划换乘路线组（支持高德云端规划与实时公交双引擎）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                // 起点输入
                OutlinedTextField(
                    value = originText,
                    onValueChange = {
                        originText = it
                        selectedOrigin = null
                        dismissOriginTips = false
                        onSearchOrigin(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("出发地 / 站点") },
                    placeholder = { Text("输入起点站名或地名…") },
                    singleLine = true,
                    trailingIcon = {
                        if (selectedOrigin != null) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )

                // 起点搜索候选
                if (isOriginTipsVisible) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    ) {
                        originTips.take(4).forEach { tip ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedOrigin = tip
                                        originText = tip.name
                                        dismissOriginTips = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tip.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (tip.district.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tip.district,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 终点输入
                OutlinedTextField(
                    value = destText,
                    onValueChange = {
                        destText = it
                        selectedDest = null
                        dismissDestTips = false
                        onSearchDest(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目的地 / 站点") },
                    placeholder = { Text("输入终点站名或商圈…") },
                    singleLine = true,
                    trailingIcon = {
                        if (selectedDest != null) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )

                // 终点搜索候选
                if (isDestTipsVisible) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    ) {
                        destTips.take(4).forEach { tip ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDest = tip
                                        destText = tip.name
                                        dismissDestTips = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = tip.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (tip.district.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tip.district,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 规划按钮
                Button(
                    onClick = {
                        val org = selectedOrigin ?: AmapTipItem(name = originText.trim(), location = "")
                        val dst = selectedDest ?: AmapTipItem(name = destText.trim(), location = "")
                        onPlanRoute(org, dst)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = originText.isNotBlank() && destText.isNotBlank() && !isPlanning
                ) {
                    if (isPlanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("高德智能计算换乘方案中…")
                    } else {
                        Icon(imageVector = Icons.AutoMirrored.Filled.AltRoute, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("开始换乘规划")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 方案结果列表
                if (plannedSchemes.isNotEmpty()) {
                    Text(
                        text = "推荐换乘路线方案 (${plannedSchemes.size}条)：",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = plannedSchemes,
                            key = { it.group.id },
                            contentType = { "amap_transit_scheme" }
                        ) { scheme ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = scheme.title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "¥${scheme.costYuan}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "约 ${scheme.durationMinutes} 分钟 · 步行 ${scheme.walkingMeters}米 · ${scheme.group.legs.size}段换乘",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { onAddScheme(scheme) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("加入路线组", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = { onStartTrackingScheme(scheme) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("立即追踪", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )

    if (showKeyDialog && onSaveCustomApiKey != null) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("高德 Web 服务 Key 设置") },
            text = {
                Column {
                    Text(
                        text = "高德开放平台为个人开发者提供每日免费云端公交/地铁规划额度。填入您的专属 Web 服务 Key 即可启用高德全量换乘规划；若留空则优先使用本地实时公交直达匹配。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("高德 Web 服务 Key") },
                        placeholder = { Text("粘贴 32 位 Web 服务 Key") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveCustomApiKey(inputKey.trim())
                        showKeyDialog = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

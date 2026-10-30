package com.trackr.ui.screens.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.TransitCodeProvider
import com.trackr.ui.components.ScheduledRemindersBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    viewModel: MoreViewModel = viewModel(),
    onNavigateToAbout: () -> Unit,
    onNavigateToLineDetail: (cityId: String, lineId: String, targetOrder: Int, stopId: String, source: DataSourceType, lineName: String) -> Unit
) {
    val engineMode by viewModel.engineMode.collectAsStateWithLifecycle()
    val nearbyStopsCount by viewModel.nearbyStopsCount.collectAsStateWithLifecycle()
    val preferredTransitCode by viewModel.preferredTransitCode.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()

    var showRemindersSheet by remember { mutableStateOf(false) }
    var engineMenuExpanded by remember { mutableStateOf(false) }
    var transitCodeMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "更多",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        // 1. 数据源切换选单
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { engineMenuExpanded = true }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "数据源",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Box {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = when (engineMode) {
                                            EngineMode.ALL -> "全部"
                                            EngineMode.CHELAILE -> "车来了"
                                            EngineMode.MYGOLBS -> "掌上公交"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "选择数据源",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                DropdownMenu(
                                    expanded = engineMenuExpanded,
                                    onDismissRequest = { engineMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("全部") },
                                        onClick = {
                                            viewModel.setEngineMode(EngineMode.ALL)
                                            engineMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("车来了") },
                                        onClick = {
                                            viewModel.setEngineMode(EngineMode.CHELAILE)
                                            engineMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("掌上公交") },
                                        onClick = {
                                            viewModel.setEngineMode(EngineMode.MYGOLBS)
                                            engineMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // 2. 默认乘车码选单
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { transitCodeMenuExpanded = true }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "默认乘车码",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Box {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = when (preferredTransitCode) {
                                            TransitCodeProvider.ASK_ALWAYS -> "每次询问"
                                            TransitCodeProvider.ALIPAY -> "支付宝"
                                            TransitCodeProvider.UNIONPAY -> "云闪付"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "选择默认乘车码",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                DropdownMenu(
                                    expanded = transitCodeMenuExpanded,
                                    onDismissRequest = { transitCodeMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("每次询问") },
                                        onClick = {
                                            viewModel.setPreferredTransitCode(TransitCodeProvider.ASK_ALWAYS)
                                            transitCodeMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("支付宝") },
                                        onClick = {
                                            viewModel.setPreferredTransitCode(TransitCodeProvider.ALIPAY)
                                            transitCodeMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("云闪付") },
                                        onClick = {
                                            viewModel.setPreferredTransitCode(TransitCodeProvider.UNIONPAY)
                                            transitCodeMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // 3. 附近站点数量调节（仅加减按钮，最大 16）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "附近站点数量",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "最多 16 个",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IconButton(
                                    onClick = { if (nearbyStopsCount > 1) viewModel.setNearbyStopsCount(nearbyStopsCount - 1) },
                                    enabled = nearbyStopsCount > 1,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "减少")
                                }

                                Text(
                                    text = "$nearbyStopsCount",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.widthIn(min = 24.dp)
                                )

                                IconButton(
                                    onClick = { if (nearbyStopsCount < 16) viewModel.setNearbyStopsCount(nearbyStopsCount + 1) },
                                    enabled = nearbyStopsCount < 16,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "增加")
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // 4. 定时通知入口
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showRemindersSheet = true }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "定时通知",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (reminders.isNotEmpty()) "${reminders.size} 个提醒" else "未设置",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "查看定时通知",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        // 5. 关于入口
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToAbout() }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "关于",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "打开关于",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRemindersSheet) {
        ScheduledRemindersBottomSheet(
            reminders = reminders,
            onDismissRequest = { showRemindersSheet = false },
            onToggleReminder = { id, enabled ->
                viewModel.toggleReminder(id, enabled)
            },
            onDeleteReminder = { id ->
                viewModel.deleteReminder(id)
            },
            onNavigateToLine = { reminder ->
                showRemindersSheet = false
                onNavigateToLineDetail(
                    reminder.cityId,
                    reminder.lineId,
                    reminder.targetOrder,
                    reminder.targetStopId,
                    reminder.dataSourceType,
                    reminder.lineName
                )
            }
        )
    }
}

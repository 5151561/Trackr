package com.trackr.ui.screens.more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trackr.domain.model.AggregatedSourcePriority
import com.trackr.domain.model.DataSourceType
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.TransitCodeProvider
import com.trackr.service.vendor.LiveUpdatePromotion
import com.trackr.service.vendor.OppoFluidCloudAdapter
import com.trackr.service.vendor.XiaomiFocusAdapter
import com.trackr.ui.components.ScheduledRemindersBottomSheet
import com.trackr.util.TransitCodeHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    viewModel: MoreViewModel = viewModel(),
    onNavigateToAbout: () -> Unit,
    onNavigateToLineDetail: (cityId: String, lineId: String, targetOrder: Int, stopId: String, source: DataSourceType, lineName: String) -> Unit
) {
    val context = LocalContext.current
    val engineMode by viewModel.engineMode.collectAsStateWithLifecycle()
    val aggregatedPriority by viewModel.aggregatedPriority.collectAsStateWithLifecycle()
    val nearbyStopsCount by viewModel.nearbyStopsCount.collectAsStateWithLifecycle()
    val preferredTransitCode by viewModel.preferredTransitCode.collectAsStateWithLifecycle()
    val isLiveNotificationEnabled by viewModel.isLiveNotificationEnabled.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()

    // 采用 rememberSaveable 确保配置变更（旋转/折叠展开）时不丢失弹窗状态
    var showRemindersSheet by rememberSaveable { mutableStateOf(false) }

    // 系统实时活动（流体云 / 焦点通知 / 置顶通知）支持与授权状态，返回前台时重新查询
    val isPromotionSupported = LiveUpdatePromotion.isPromotionSupported() ||
            XiaomiFocusAdapter.isXiaomiRom() ||
            OppoFluidCloudAdapter.isColorOs()

    val queryPromotionAllowed: () -> Boolean = {
        when {
            XiaomiFocusAdapter.isXiaomiRom() -> XiaomiFocusAdapter.canShowFocus(context)
            LiveUpdatePromotion.isPromotionSupported() -> LiveUpdatePromotion.canPromote(context)
            else -> false
        }
    }

    var canPromoteLiveUpdate by remember { mutableStateOf(queryPromotionAllowed()) }
    LifecycleResumeEffect(Unit) {
        canPromoteLiveUpdate = queryPromotionAllowed()
        onPauseOrDispose { }
    }

    // 向上滑动时标题栏平滑向上收起为单行栏，向下拉时展开
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "更多",
                        fontWeight = FontWeight.Bold
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 80.dp + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. 数据与引擎设置分组 (Data & Engine)
            item(key = "data_engine_section", contentType = "more_section") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "数据与检索",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // 1.1 实时数据源切换
                            Text(
                                text = "实时数据源",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "聚合模式下智能互补两大数据源，获得最优到站精度",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            val engineOptions = listOf(
                                EngineMode.CHELAILE to "车来了",
                                EngineMode.MYGOLBS to "掌上公交"
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                engineOptions.forEachIndexed { index, (mode, label) ->
                                    SegmentedButton(
                                        selected = engineMode == mode,
                                        onClick = { viewModel.setEngineMode(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = engineOptions.size
                                        ),
                                        label = {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (engineMode == mode) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(modifier = Modifier.height(14.dp))

                            // 1.2 附近站点搜索上限
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "附近站点搜索上限",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "首页就近检索的车站数量",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = "$nearbyStopsCount 个",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = { if (nearbyStopsCount > 1) viewModel.setNearbyStopsCount(nearbyStopsCount - 1) },
                                    enabled = nearbyStopsCount > 1,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "减少站点数量")
                                }

                                Slider(
                                    value = nearbyStopsCount.toFloat(),
                                    onValueChange = { viewModel.setNearbyStopsCount(it.toInt()) },
                                    valueRange = 1f..16f,
                                    steps = 14,
                                    modifier = Modifier.weight(1f)
                                )

                                IconButton(
                                    onClick = { if (nearbyStopsCount < 16) viewModel.setNearbyStopsCount(nearbyStopsCount + 1) },
                                    enabled = nearbyStopsCount < 16,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "增加站点数量")
                                }
                            }
                        }
                    }
                }
            }

            // 2. 出行辅助与工具分组 (Transit Assistance & Tools)
            item(key = "transit_tools_section", contentType = "more_section") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "出行辅助与工具",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // 2.1 默认乘车码切换
                            Text(
                                text = "默认乘车码",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "快捷方式或扫码乘车时优先唤起的乘车码平台",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            val transitCodeOptions = listOf(
                                TransitCodeProvider.ASK_ALWAYS to "每次询问",
                                TransitCodeProvider.ALIPAY to "支付宝",
                                TransitCodeProvider.UNIONPAY to "云闪付"
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                transitCodeOptions.forEachIndexed { index, (provider, label) ->
                                    SegmentedButton(
                                        selected = preferredTransitCode == provider,
                                        onClick = { viewModel.setPreferredTransitCode(provider) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = transitCodeOptions.size
                                        ),
                                        label = {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (preferredTransitCode == provider) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    )
                                }
                            }

                            if (preferredTransitCode != TransitCodeProvider.ASK_ALWAYS) {
                                Spacer(modifier = Modifier.height(10.dp))
                                FilledTonalButton(
                                    onClick = { TransitCodeHelper.openTransitCode(context, preferredTransitCode) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.QrCode2,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when (preferredTransitCode) {
                                            TransitCodeProvider.ALIPAY -> "测试打开支付宝乘车码"
                                            TransitCodeProvider.UNIONPAY -> "测试打开云闪付乘车码"
                                            else -> "测试打开乘车码"
                                        },
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(modifier = Modifier.height(4.dp))

                            // 2.2 定时通勤提醒入口
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showRemindersSheet = true }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Alarm,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "定时通勤提醒",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "在设定时间自动开启常驻通知追踪线路",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (reminders.isNotEmpty()) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    }
                                ) {
                                    Text(
                                        text = if (reminders.isNotEmpty()) "${reminders.size} 个提醒" else "未设置",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (reminders.isNotEmpty()) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "查看定时通知",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Spacer(modifier = Modifier.height(10.dp))

                            // 2.3 实时在途通知（灵动胶囊 / 实时活动）
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.NotificationsActive,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "实时通知（状态栏胶囊 / 实时活动）",
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "在状态栏胶囊与锁屏显示公交到达进度。支持 Android 16 实时活动、小米澎湃OS 焦点通知及 OPPO 流体云（需开启系统「置顶通知」）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Switch(
                                        checked = isLiveNotificationEnabled,
                                        onCheckedChange = { viewModel.setLiveNotificationEnabled(it) }
                                    )
                                }

                                if (isLiveNotificationEnabled) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = if (!isPromotionSupported) {
                                            "当前系统版本以通知栏常驻卡片形式展示在途进度"
                                        } else if (canPromoteLiveUpdate) {
                                            "系统实时活动权限已开启，可在状态栏胶囊 / 流体云中显示"
                                        } else {
                                            "系统实时活动权限未开启，胶囊与流体云不会显示"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (!isPromotionSupported) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else if (canPromoteLiveUpdate) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    FilledTonalButton(
                                        onClick = {
                                            when {
                                                !isPromotionSupported ->
                                                    LiveUpdatePromotion.openAppNotificationSettings(context)
                                                !canPromoteLiveUpdate ->
                                                    LiveUpdatePromotion.openPromotionSettings(context)
                                                XiaomiFocusAdapter.isXiaomiRom() ->
                                                    XiaomiFocusAdapter.openNotificationSettings(context)
                                                OppoFluidCloudAdapter.isColorOs() ->
                                                    OppoFluidCloudAdapter.openFluidCloudSettings(context)
                                                else ->
                                                    LiveUpdatePromotion.openAppNotificationSettings(context)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (!isPromotionSupported) {
                                                "打开应用通知设置"
                                            } else if (canPromoteLiveUpdate) {
                                                "打开系统流体云 / 焦点通知设置"
                                            } else {
                                                "去开启实时活动 / 置顶通知权限"
                                            },
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. 关于与支持分组 (About & Support)
            item(key = "about_support_section", contentType = "more_section") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "关于与支持",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onNavigateToAbout() }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "关于应用与开源致谢",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "版本声明、开源协议与代码仓库",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

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

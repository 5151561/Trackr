package com.trackr.feature.bus.elder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_boarded
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.designsystem.theme.eta
import com.trackr.feature.bus.resources.Res as BusRes
import com.trackr.feature.bus.resources.bus_elder_arrived_go
import com.trackr.feature.bus.resources.bus_elder_arrived_title
import com.trackr.feature.bus.resources.bus_elder_arrived_transit_code
import com.trackr.ui.components.elder.ElderButtonHeight
import com.trackr.ui.components.elder.ElderLineBadge
import com.trackr.ui.components.elder.ElderPrimaryButtonHeight
import com.trackr.ui.components.transitcode.TransitCodeChooserSheet
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** 测试用的 tag */
internal object ElderArrivalAlertTags {
    const val ROOT = "elder_arrival_alert"
    const val TRANSIT_CODE = "elder_arrival_transit_code"
    const val BOARDED = "elder_arrival_boarded"
}

/**
 * 长辈版到站整屏提醒的有状态外壳：放在长辈版导航的最上层（`ElderNavApp`），盖住当前页。
 * 没有要弹的就什么都不画。
 */
@Composable
fun ElderArrivalAlertHost(viewModel: ArrivalAlertViewModel = koinViewModel()) {
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val sheetOpen by viewModel.transitCodeSheetOpen.collectAsStateWithLifecycle()
    alert?.let { ElderArrivalAlert(alert = it, onEvent = viewModel::onEvent) }
    if (sheetOpen) {
        TransitCodeChooserSheet(
            onDismiss = { viewModel.onEvent(ArrivalAlertEvent.DismissTransitCodeSheet) },
            onSelect = { provider, save -> viewModel.onEvent(ArrivalAlertEvent.PickTransitCode(provider, save)) }
        )
    }
}

/**
 * 到站整屏提醒（设计稿「车到站」）：整屏变绿，「325  → 科技园」「车到站了！」「快去科技园站上车」，
 * 下面两个大按钮「出示乘车码」「我上车了」。系统返回只关掉这一次（不结束追踪）。
 */
@Composable
fun ElderArrivalAlert(alert: ArrivalAlertUi, onEvent: (ArrivalAlertEvent) -> Unit, modifier: Modifier = Modifier) {
    val dimens = TrackrTheme.dimens
    // 浅色主题 go 是深绿配白字，深色主题 go 是亮绿配深色字；surface 两边都对得上（同「出示乘车码」）
    val background = TrackrTheme.colors.go
    val onBackground = MaterialTheme.colorScheme.surface

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = { onEvent(ArrivalAlertEvent.Dismiss) }
    )

    Surface(
        color = background,
        contentColor = onBackground,
        modifier = modifier
            .fillMaxSize()
            .testTag(ElderArrivalAlertTags.ROOT)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceL)
        ) {
            Spacer(modifier = Modifier.heightIn(min = dimens.spaceXl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ElderLineBadge(name = alert.lineName)
                if (alert.endStop.isNotBlank()) {
                    Spacer(modifier = Modifier.width(dimens.spaceM))
                    Text(
                        text = "→ ${alert.endStop}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            val titleStyle = MaterialTheme.typography.eta.copy(color = onBackground, textAlign = TextAlign.Start)
            BasicText(
                text = stringResource(BusRes.string.bus_elder_arrived_title),
                style = titleStyle,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = titleStyle.fontSize),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(BusRes.string.bus_elder_arrived_go, stationName(alert.stopName)),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            // 能滚动（字放到 2 倍时一屏放不下），所以按钮不用 weight 顶到底，空一段就行
            Spacer(modifier = Modifier.heightIn(min = dimens.spaceXl))
            Button(
                onClick = { onEvent(ArrivalAlertEvent.OpenTransitCode) },
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = onBackground, contentColor = background),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ElderPrimaryButtonHeight)
                    .testTag(ElderArrivalAlertTags.TRANSIT_CODE)
            ) {
                Text(
                    text = stringResource(BusRes.string.bus_elder_arrived_transit_code),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedButton(
                onClick = { onEvent(ArrivalAlertEvent.Boarded) },
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(2.dp, onBackground),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = onBackground),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ElderButtonHeight)
                    .testTag(ElderArrivalAlertTags.BOARDED)
            ) {
                Text(
                    text = stringResource(Res.string.action_boarded),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 「科技园」→「科技园站」，已经带「站」的不重复 */
internal fun stationName(stopName: String): String = when {
    stopName.isBlank() -> ""
    stopName.endsWith("站") -> stopName
    else -> "${stopName}站"
}

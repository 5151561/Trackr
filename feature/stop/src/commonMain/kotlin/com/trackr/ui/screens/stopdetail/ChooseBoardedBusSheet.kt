package com.trackr.ui.screens.stopdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.feature.stop.resources.Res as StopRes
import com.trackr.feature.stop.resources.stop_choose_title
import com.trackr.feature.stop.resources.stop_not_yet
import com.trackr.ui.icons.TrackrIcons
import org.jetbrains.compose.resources.stringResource

/**
 * 「上了哪辆？」（§2.6 第 ④ 条）：点「我上车了」时不同线的车同时在站、定位又分不清才有。
 * 是导航弹层 `ScreenKey.ChooseBoardedBus`：外面的 ModalBottomSheet 由 `BottomSheetSceneStrategy` 套，这里只画内容。
 * 选完、或待选已经没了（[ChooseBoardedBusState.Closed]）就调 [onClose] 把自己从栈里拿掉。
 */
@Composable
internal fun ChooseBoardedBusRoute(viewModel: ChooseBoardedBusViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state == ChooseBoardedBusState.Closed) onClose()
    }
    when (val current = state) {
        is ChooseBoardedBusState.Showing -> ChooseBoardedBusContent(
            choice = current.choice,
            onChoose = { busId ->
                viewModel.choose(busId)
                onClose()
            }
        )
        // 读会话的那一下：先占个高度，不闪一下空弹层又收起
        ChooseBoardedBusState.Loading, ChooseBoardedBusState.Closed -> Spacer(Modifier.fillMaxWidth().height(120.dp))
    }
}

/** 弹层内容（测试直接渲染它） */
@Composable
fun ChooseBoardedBusContent(
    choice: BoardChoiceUi,
    onChoose: (busId: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
        Text(
            text = stringResource(StopRes.string.stop_choose_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = choice.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                choice.options.forEachIndexed { index, option ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onChoose(option.busId) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LineBadge(option.lineName)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (option.endStop.isNotBlank()) {
                                Text(
                                    text = "→ ${option.endStop}",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (option.plate.isNotBlank()) {
                                Text(
                                    text = option.plate,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = TrackrIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        TextButton(
            onClick = { onChoose(null) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text(stringResource(StopRes.string.stop_not_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

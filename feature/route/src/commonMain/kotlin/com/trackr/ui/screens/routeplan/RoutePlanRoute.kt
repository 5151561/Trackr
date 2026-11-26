package com.trackr.ui.screens.routeplan

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.core.model.CandidateLine
import com.trackr.ui.screens.favoritesgroup.boardStopOrderForDetail
import com.trackr.ui.screens.routedetail.RouteDetailViewModel
import com.trackr.ui.screens.routeplan.map.isRouteMapSupported

/**
 * 规划页的 Route：收两个 ViewModel 的状态拼成 [RoutePlanUiState]，把事件分给它们，显示一次性提示。
 * - 追踪（开始 / 我上车了 / 结束）和追踪提示在 [RouteDetailViewModel]（和收藏页共用一个实例）；
 * - 其余在 [RoutePlanViewModel]。
 */
@Composable
fun RoutePlanRoute(
    viewModel: RoutePlanViewModel,
    routeDetailViewModel: RouteDetailViewModel,
    /** 「去看看」：打开收藏页 */
    onNavigateToFavorites: () -> Unit,
    /** 点线路胶囊：线路详情定位到上车站 */
    onNavigateToLineDetail: (line: CandidateLine, targetOrder: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val planState by viewModel.uiState.collectAsStateWithLifecycle()
    val trackingSession by routeDetailViewModel.activeGroupSession.collectAsStateWithLifecycle()
    val messages by viewModel.userMessages.collectAsStateWithLifecycle()

    // 追踪对齐失败等一次性提示：转进规划页的 userMessages，和「已收藏」走同一个 Snackbar
    val trackingMessage by routeDetailViewModel.trackingMessage.collectAsStateWithLifecycle()
    LaunchedEffect(trackingMessage) {
        trackingMessage?.let {
            viewModel.postMessage(it)
            routeDetailViewModel.clearTrackingMessage()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val goToFavorites by rememberUpdatedState(onNavigateToFavorites)
    val message = messages.firstOrNull()
    LaunchedEffect(message?.id) {
        message ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message.text,
            actionLabel = message.action?.label,
            duration = if (message.action != null) SnackbarDuration.Short else SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed && message.action == RoutePlanMessageAction.GoToFavorites) {
            goToFavorites()
        }
        viewModel.onEvent(RoutePlanEvent.MessageShown(message.id))
    }

    // 输入框文字是 VM 里的快照状态，这里同步读，输入法组字时不跳光标
    val state = planState.copy(
        originText = viewModel.originText,
        destText = viewModel.destText,
        canShowMap = planState.canShowMap && isRouteMapSupported,
        trackingSession = trackingSession
    )

    RoutePlanScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                is RoutePlanEvent.OpenLine -> onNavigateToLineDetail(event.line, event.line.boardStopOrderForDetail())
                // 只追踪这一次：不加入收藏，留在规划页
                is RoutePlanEvent.StartTracking -> routeDetailViewModel.startGroupTracking(event.scheme.group)
                RoutePlanEvent.ConfirmBoarded -> routeDetailViewModel.confirmBoarded()
                RoutePlanEvent.StopTracking -> routeDetailViewModel.stopGroupTracking()
                else -> viewModel.onEvent(event)
            }
        },
        modifier = modifier,
        snackbarHostState = snackbarHostState
    )
}

package com.trackr.ui.screens.routeplan

import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupTrackingSessionState
import com.trackr.ui.screens.routeplan.map.RouteMapState

/** 输入提示列表当前给哪个输入框 */
enum class TipsField { Origin, Dest }

/** 规划页（无状态 [RoutePlanScreen]）要的全部数据 */
data class RoutePlanUiState(
    val originText: String = "",
    val destText: String = "",
    /** 正在显示哪个输入框的提示；null 表示不显示 */
    val tipsField: TipsField? = null,
    val tips: List<AmapTipItem> = emptyList(),
    val isPlanning: Boolean = false,
    val planError: String? = null,
    /** 最多 3 个方案标签（[pickSchemeTabs]） */
    val schemeTabs: List<SchemeTabUi> = emptyList(),
    val selectedTab: Int = 0,
    /** 画不画地图区：有 Android Key 且平台有地图实现（D2、Q18） */
    val canShowMap: Boolean = false,
    val map: RouteMapState = RouteMapState(),
    val favoriteGroups: List<FavoriteGroup> = emptyList(),
    val showSaveDialog: Boolean = false,
    /** 正在追踪的路线（`RouteDetailViewModel` 给的，由 Route 填进来） */
    val trackingSession: GroupTrackingSessionState? = null
) {
    val currentScheme: AmapTransitSchemeResult?
        get() = schemeTabs.getOrNull(selectedTab)?.scheme
}

/** 一次性提示（Snackbar），显示完调 [RoutePlanEvent.MessageShown] */
data class RoutePlanMessage(
    val id: Long,
    val text: String,
    val action: RoutePlanMessageAction? = null
)

enum class RoutePlanMessageAction(val label: String) {
    /** 收藏后「去看看」：打开收藏页 */
    GoToFavorites("去看看")
}

/** 页面发出的事件 */
sealed interface RoutePlanEvent {
    data class OriginChanged(val text: String) : RoutePlanEvent
    data class DestChanged(val text: String) : RoutePlanEvent
    data object ClearOrigin : RoutePlanEvent
    data object ClearDest : RoutePlanEvent
    data object SwapOriginDest : RoutePlanEvent
    /** 键盘「搜索」或右边的规划按钮 */
    data object Submit : RoutePlanEvent
    /** 出错后「重新规划」 */
    data object Retry : RoutePlanEvent
    data class PickTip(val field: TipsField, val tip: AmapTipItem) : RoutePlanEvent
    /** 空输入框里点「我的位置」选项 */
    data class PickMyLocation(val field: TipsField) : RoutePlanEvent
    /** 返回键先收起提示 */
    data object DismissTips : RoutePlanEvent
    data class SelectScheme(val index: Int) : RoutePlanEvent
    data class OpenLine(val line: CandidateLine) : RoutePlanEvent
    data object OpenSaveDialog : RoutePlanEvent
    data object DismissSaveDialog : RoutePlanEvent
    data class ConfirmSave(val groupId: String?, val name: String) : RoutePlanEvent
    data class CreateGroup(val name: String) : RoutePlanEvent
    /** 只追踪这一次，不收藏（不做「收藏并开始追踪」） */
    data class StartTracking(val scheme: AmapTransitSchemeResult) : RoutePlanEvent
    data object ConfirmBoarded : RoutePlanEvent
    data object StopTracking : RoutePlanEvent
    data class MessageShown(val id: Long) : RoutePlanEvent
}

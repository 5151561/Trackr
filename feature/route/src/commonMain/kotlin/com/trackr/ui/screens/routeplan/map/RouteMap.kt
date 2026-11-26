package com.trackr.ui.screens.routeplan.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.GeoPoint

/** 地图要的数据；点都已经是 GCJ-02 */
data class RouteMapState(
    /** 方案标签对应的方案：选中的画粗彩线，其余画细灰线 */
    val schemes: List<AmapTransitSchemeResult> = emptyList(),
    val selectedIndex: Int = 0,
    /** 当前定位（GCJ-02）；没有方案时用来取景，null 表示还没定位 */
    val myLocation: GeoPoint? = null,
    /** 当前城市中心（GCJ-02）；定位不可用时的取景兜底，null 时相机不动 */
    val cityCenter: GeoPoint? = null,
    /** 用户填的高德 Android Key（地图 SDK 用）；只有 Web Key 时为 null */
    val androidKey: String? = null
)

/** 这个平台有没有地图实现：Android 有（高德 SDK）；iOS 第 38 步之前没有（Q15、Q18） */
expect val isRouteMapSupported: Boolean

/** 测试和平台实现共用的「不画地图」底色区的 testTag */
const val ROUTE_PLAN_NO_MAP_TAG = "route_plan_no_map"

/**
 * 规划页的全屏地图（含回到定位按钮、底图加载失败浮层）。
 * Android：高德 3D 地图（`AndroidView`，实例由 Activity 级 `AmapMapSession` 托管，随 Activity 暂停 / 恢复）；
 * 地图建不出来时只画底色区。iOS：第 38 步接高德 iOS SDK，之前只画底色区。
 *
 * @param bottomInset 底部方案面板的实时高度；回到定位按钮贴着它的上沿，跟着面板一起上下移动
 */
@Composable
expect fun RouteMap(
    state: RouteMapState,
    modifier: Modifier = Modifier,
    bottomInset: () -> Dp = { 0.dp }
)

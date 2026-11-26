package com.trackr.ui.screens.routeplan.map

import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.trackr.core.designsystem.theme.extendedColors
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.GeoPoint
import com.trackr.ui.screens.routeplan.AmapMapSession
import kotlinx.coroutines.delay

private fun GeoPoint.toLatLng(): LatLng = LatLng(lat, lng)

/** 选中方案只有一个点（或所有点几乎重合）时的取景级别：街道级，能看清上下车站 */
private const val SINGLE_POINT_ZOOM = 16f

/**
 * 真实高德 3D 矢量地图视图（TextureMapView），实例由 [AmapMapSession] 托管。
 * 第 24 步从旧 `RoutePlanScreen.kt` 搬来，画什么由 [RouteMapGeometry]（commonMain）算。
 *
 * 地图实例不随本 composable 创建 / 销毁：切走页签只是把容器从窗口摘下（onPause），
 * 回来时同一实例重新挂上（onResume），所以不会重新初始化、也不会丢相机位置。
 *
 * @param session Activity 级地图会话，负责实例复用与生命周期
 * @param schemes 方案标签对应的方案：选中的画粗彩线，其余画细灰线，方便对比
 * @param myLocation 当前设备定位（GCJ-02）；没有方案时用于取景，null 表示暂无定位
 * @param cityCenter 当前城市中心（GCJ-02）；定位不可用时的取景兜底，null 时保持相机不动
 * @param darkMode 是否深色，决定地图是否用夜间样式
 * @param retryToken 变化时丢弃当前实例并重建（用户点「重试」）
 * @param onMapLoaded 底图首次就绪回调
 * @param onLoadTimeout 底图超时未就绪回调（软失败，外层以浮层提示，不销毁地图）
 * @param onRuntimeError 地图实例创建失败（如设备 ABI 缺 arm 库）回调，外层不画地图并浮层提示、可重试
 */
@Composable
internal fun AmapRealVectorMapView(
    session: AmapMapSession,
    schemes: List<AmapTransitSchemeResult>,
    selectedIndex: Int,
    customApiKey: String?,
    darkMode: Boolean = false,
    myLocation: GeoPoint? = null,
    cityCenter: GeoPoint? = null,
    recenterTrigger: Int = 0,
    retryToken: Int = 0,
    modifier: Modifier = Modifier,
    onMapLoaded: () -> Unit = {},
    onLoadTimeout: (String) -> Unit = {},
    onRuntimeError: (String) -> Unit = {}
) {
    val aMap = session.aMapState.value
    val isMapLoaded = session.isLoadedState.value
    val runtimeError = session.runtimeErrorState.value

    // 取景内边距要按地图可视尺寸算（底部方案面板会盖住画面下约 40%），所以先量一下自己的尺寸
    var mapSize by remember { mutableStateOf(IntSize.Zero) }

    AndroidView(
        factory = { _ ->
            // 容器可能是从上一个页面「摘下」的，先确保它没有残留父节点，否则 addView 会抛异常
            (session.container.parent as? ViewGroup)?.removeView(session.container)
            // Key 必须在 SDK 创建地图实例前注入
            session.updateKey(customApiKey)
            session.container
        },
        modifier = modifier.onSizeChanged { mapSize = it }
    )

    LaunchedEffect(customApiKey) { session.updateKey(customApiKey) }
    LaunchedEffect(darkMode) { session.setDarkMode(darkMode) }

    // retryToken 来自 remember，首次组合可能带着历史值，只在真正变化时重建
    var handledRetryToken by remember { mutableIntStateOf(retryToken) }
    LaunchedEffect(retryToken) {
        if (retryToken != handledRetryToken) {
            handledRetryToken = retryToken
            session.retry()
        }
    }

    LaunchedEffect(runtimeError) {
        runtimeError?.let { onRuntimeError(it) }
    }

    // 底图长时间未就绪：多为 Key 未绑定当前包名 / SHA-1（高德鉴权失败时不会抛异常，只是不出图）
    val appPackageName = LocalContext.current.packageName
    LaunchedEffect(aMap, isMapLoaded) {
        if (aMap == null) return@LaunchedEffect
        if (isMapLoaded) {
            onMapLoaded()
            return@LaunchedEffect
        }
        delay(15_000)
        if (!session.isLoadedState.value) {
            onLoadTimeout(
                "底图 15 秒内未加载完成。最常见原因是 Key 与当前运行环境不匹配：" +
                    "包名（${appPackageName}）或签名 SHA-1 与高德控制台填写的不一致，" +
                    "或误把「Web 服务 Key」当成了地图 Key。可点「诊断信息」核对具体数值。"
            )
        }
    }

    // 折线颜色取自主题（高德 SDK 需要 ARGB Int）
    val leg1ColorArgb = MaterialTheme.colorScheme.primary.toArgb()
    val leg2ColorArgb = MaterialTheme.extendedColors.legSecondary.toArgb()
    val walkColorArgb = MaterialTheme.extendedColors.walk.toArgb()
    val otherSchemeColorArgb = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f).toArgb()

    // 绘制层：方案列表 / 选中方案 / 主题色变化时重绘。只画线，不动相机：切换方案不重新缩放，方便直接对比。
    LaunchedEffect(schemes, selectedIndex, aMap, isMapLoaded, leg1ColorArgb, leg2ColorArgb, walkColorArgb, otherSchemeColorArgb) {
        if (aMap == null || !isMapLoaded) return@LaunchedEffect
        drawSchemes(
            aMap = aMap,
            schemes = schemes,
            selectedIndex = selectedIndex,
            colors = OverlayColors(leg1ColorArgb, leg2ColorArgb, walkColorArgb, otherSchemeColorArgb)
        )
    }

    // 取景层（有方案时）：以**当前选中方案**为主角 fit bounds——选中方案是粗彩线的主角，
    // 其他方案只是细灰线参考，不能把视角拉走（以前合并全部方案，3 个目的地各不相同，直接缩到全市）。
    // key 只放方案列表 / 选中下标 / 重置按钮：用户手动拖动地图时这些值不变，不会被反复拉回去。
    LaunchedEffect(schemes, selectedIndex, aMap, isMapLoaded, recenterTrigger, mapSize) {
        if (aMap == null || !isMapLoaded) return@LaunchedEffect
        // 内边距要按真实可视尺寸算；尺寸还没量出来就先不动相机（onSizeChanged 到了会重跑）
        if (mapSize.width <= 0 || mapSize.height <= 0) return@LaunchedEffect
        val framing = mapFramingFor(schemes, selectedIndex) ?: return@LaunchedEffect
        val padding = mapFramingPadding(mapSize.width, mapSize.height)
        val camera = when (framing) {
            is MapFraming.Bounds -> CameraUpdateFactory.newLatLngBoundsRect(
                LatLngBounds(
                    LatLng(framing.south, framing.west),
                    LatLng(framing.north, framing.east)
                ),
                padding.left,
                padding.right,
                padding.top,
                padding.bottom
            )
            is MapFraming.Point -> CameraUpdateFactory.newLatLngZoom(framing.point.toLatLng(), SINGLE_POINT_ZOOM)
        }
        runCatching { aMap.animateCamera(camera) }.onFailure {
            val anchor = when (framing) {
                is MapFraming.Bounds -> GeoPoint(framing.south, framing.west)
                is MapFraming.Point -> framing.point
            }
            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(anchor.toLatLng(), 14f))
        }
    }

    // 取景层：负责「首次进入 / 点击重置」时的相机位置。
    // 优先级：我的定位 > 当前城市中心（已转 GCJ-02，301c124）> 不动（绝不硬编码某个城市）。
    LaunchedEffect(aMap, isMapLoaded, schemes.isNotEmpty(), myLocation, cityCenter, recenterTrigger) {
        if (aMap == null || !isMapLoaded) return@LaunchedEffect
        // 有算路方案时由方案自己 fit bounds，不抢相机
        if (schemes.isNotEmpty()) {
            session.hasCenteredOnce = true
            session.handledRecenterTrigger = recenterTrigger
            return@LaunchedEffect
        }
        val isUserRecenter = recenterTrigger != session.handledRecenterTrigger
        // 定位迟到（先按城市中心取景，之后才拿到 GPS）时补一次跟随
        val isFirstUserFix = myLocation != null && !session.centeredWithMyLocation
        if (session.hasCenteredOnce && !isUserRecenter && !isFirstUserFix) return@LaunchedEffect

        val target = (myLocation ?: cityCenter ?: return@LaunchedEffect).toLatLng()
        session.hasCenteredOnce = true
        session.handledRecenterTrigger = recenterTrigger
        if (myLocation != null) session.centeredWithMyLocation = true
        aMap.animateCamera(
            CameraUpdateFactory.newLatLngZoom(target, if (myLocation != null) 15f else 12f)
        )
    }
}

/** 覆盖物颜色（ARGB） */
internal data class OverlayColors(
    val leg1: Int,
    val leg2: Int,
    val walk: Int,
    val otherScheme: Int
)

/** 清掉方案覆盖物再按 [RouteMapGeometry] 画：其他方案细灰线，选中方案粗彩线 + 标记 */
internal fun drawSchemes(
    aMap: AMap,
    schemes: List<AmapTransitSchemeResult>,
    selectedIndex: Int,
    colors: OverlayColors
) {
    // clear(true)：保留定位蓝点，只清掉方案覆盖物（301c124）
    aMap.clear(true)

    // 1. 其他方案：细灰半透明线
    schemes.forEachIndexed { index, scheme ->
        if (index == selectedIndex) return@forEachIndexed
        schemeLines(scheme).forEach { line ->
            val opts = PolylineOptions()
                .color(colors.otherScheme)
                .width(if (line.isWalk) 6f else 8f)
                .setDottedLine(line.isWalk)
            line.points.forEach { opts.add(it.toLatLng()) }
            aMap.addPolyline(opts)
        }
    }

    // 2. 选中方案：按形状点画粗彩线 + 换乘 / 起终点标记（743e932）
    val selected = schemes.getOrNull(selectedIndex) ?: return
    selectedSchemeOverlays(selected).forEach { overlay ->
        when (overlay) {
            is RouteMapOverlay.Walk -> {
                val opts = PolylineOptions().color(colors.walk).width(12f).setDottedLine(true)
                overlay.points.forEach { opts.add(it.toLatLng()) }
                aMap.addPolyline(opts)
            }
            is RouteMapOverlay.Ride -> {
                val color = if (overlay.legIndex == 0) colors.leg1 else colors.leg2
                val opts = PolylineOptions().color(color).width(18f)
                overlay.points.forEach { opts.add(it.toLatLng()) }
                aMap.addPolyline(opts)
            }
            is RouteMapOverlay.Marker -> {
                aMap.addMarker(MarkerOptions().position(overlay.point.toLatLng()).title(overlay.title))
            }
        }
    }
}

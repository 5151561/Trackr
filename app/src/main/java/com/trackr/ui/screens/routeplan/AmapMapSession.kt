package com.trackr.ui.screens.routeplan

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import com.amap.api.maps.AMap
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.MyLocationStyle
import com.trackr.data.remote.amap.AmapSdkManager

/**
 * 地图实例的「会话」：把 TextureMapView 托管到 Compose 生命周期之外。
 *
 * 背景：路线规划页挂在 NavDisplay 的 entry 上，切走 tab 时整个 composable 会被 dispose。
 * 如果地图跟着 composable 一起销毁，回来时就得重新初始化 GL、重新拉瓦片，相机位置也丢，
 * 视觉上就是"每次切回来都要重新加载"。
 *
 * 这里用一个 Activity 级的容器持有地图实例：
 * - 切走：只把 container 从窗口摘下来（onPause），地图实例、瓦片缓存、相机位置都留着；
 * - 回来：同一个实例重新挂上去（onResume），已绘制的覆盖物原样保留；
 * - 按 Home / 锁屏（Activity ON_PAUSE）：调用 [onHostPause] 暂停内置定位与 GL 渲染，
 *   回到前台（ON_RESUME）且容器仍挂在窗口上时由 [onHostResume] 恢复；
 * - Activity 真正销毁时才 onDestroy。
 *
 * 注意：onCreate 必须等 view attach 到窗口之后再调用，否则高德 SDK 会卡在初始化状态，
 * 表现为引擎可读相机但底图永不渲染、onMapLoaded 不回调（黑屏）。
 */
class AmapMapSession(private val activity: Activity) {

    /** 稳定的宿主容器，交给 AndroidView 复用；它本身不随 composable 销毁 */
    val container: FrameLayout = FrameLayout(activity)

    private var mapView: TextureMapView? = null
    private var isCreated = false
    private var isResumed = false
    private var isDestroyed = false

    /** 宿主 Activity 是否处于 pause 状态（按 Home / 锁屏）；暂停期间即使容器挂上窗口也不 onResume */
    private var isHostPaused = false

    private var appliedKey: String? = null
    private var darkMode = false

    /** 地图控制器，null 表示尚未创建就绪 */
    val aMapState = mutableStateOf<AMap?>(null)

    /** 底图首帧是否已渲染完成（onMapLoaded） */
    val isLoadedState = mutableStateOf(false)

    /** 创建失败原因（如设备 ABI 缺原生库），外层据此降级为矢量示意图 */
    val runtimeErrorState = mutableStateOf<String?>(null)

    // 取景状态同样跨页面保留：否则切走再回来会把相机重新拉回定位点，
    // 用户手动拖到的位置就白拖了。
    var hasCenteredOnce = false
    var centeredWithMyLocation = false
    var handledRecenterTrigger = 0

    init {
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // 等一帧，确保窗口与尺寸都已稳定，再走 SDK 生命周期（与官方 Demo 一致）
                v.post { ensureCreated() }
            }

            override fun onViewDetachedFromWindow(v: View) {
                pause()
            }
        })
    }

    /** 注入/切换高德 Key；Key 变化必须重建实例（SDK 只在创建时读取一次） */
    fun updateKey(customApiKey: String?) {
        if (customApiKey == appliedKey) return
        appliedKey = customApiKey
        if (isCreated) recreate()
    }

    /** 跟随应用主题切换地图夜间样式 */
    fun setDarkMode(dark: Boolean) {
        if (dark == darkMode) return
        darkMode = dark
        applyMapType()
    }

    /** 宿主 Activity ON_PAUSE：暂停地图（停止内置定位与渲染） */
    fun onHostPause() {
        isHostPaused = true
        pause()
    }

    /** 宿主 Activity ON_RESUME：容器仍挂在窗口上时恢复地图 */
    fun onHostResume() {
        isHostPaused = false
        if (container.isAttachedToWindow) resume()
    }

    /** 用户主动重试：丢弃当前实例重新创建 */
    fun retry() {
        if (isCreated) recreate()
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        runCatching {
            if (isResumed) mapView?.onPause()
            mapView?.onDestroy()
        }
        isResumed = false
        container.removeAllViews()
        mapView = null
        isCreated = false
        isLoadedState.value = false
        aMapState.value = null
        resetCentering()
    }

    private fun ensureCreated() {
        if (isDestroyed || activity.isFinishing || activity.isDestroyed) return
        if (isCreated) {
            resume()
            return
        }

        AmapSdkManager.applyApiKey(activity, appliedKey, force = true)

        val view = try {
            TextureMapView(activity)
        } catch (t: Throwable) {
            runtimeErrorState.value = "高德地图初始化失败：${t.javaClass.simpleName}。" +
                "3dmap SDK 仅内置 arm64-v8a / armeabi-v7a 原生库，模拟器请使用 arm 架构镜像。"
            return
        }

        runCatching { view.onCreate(null) }.onFailure { t ->
            runtimeErrorState.value = "高德地图初始化失败：${t.javaClass.simpleName}"
            return
        }

        container.addView(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        mapView = view
        isCreated = true

        val map = view.map
        map.uiSettings.apply {
            isZoomControlsEnabled = false
            isCompassEnabled = false
            isScaleControlsEnabled = true
            isMyLocationButtonEnabled = false
        }

        map.setOnMapLoadedListener {
            isLoadedState.value = true
            // 蓝点交给 SDK 自己画（GCJ-02），但必须等底图首帧就绪后再开，
            // 否则引擎未创建时定位回调触发的相机动画会让 SDK 内部空指针。
            runCatching {
                map.myLocationStyle = MyLocationStyle()
                    .myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
                    .interval(4000L)
                    .showMyLocation(true)
                map.isMyLocationEnabled = true
            }
        }

        aMapState.value = map
        applyMapType()
        resume()
    }

    /** 地图实例重建后需要重新取景 */
    private fun resetCentering() {
        hasCenteredOnce = false
        centeredWithMyLocation = false
        handledRecenterTrigger = 0
    }

    private fun applyMapType() {
        val map = aMapState.value ?: return
        runCatching {
            map.mapType = if (darkMode) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
        }
    }

    private fun resume() {
        if (isDestroyed || !isCreated || isResumed || isHostPaused) return
        isResumed = true
        runCatching { mapView?.onResume() }
    }

    private fun pause() {
        if (!isCreated || !isResumed) return
        isResumed = false
        runCatching { mapView?.onPause() }
    }

    private fun recreate() {
        val view = mapView ?: return
        runCatching {
            if (isResumed) view.onPause()
            view.onDestroy()
        }
        isResumed = false
        container.removeAllViews()
        mapView = null
        isCreated = false
        isLoadedState.value = false
        aMapState.value = null
        resetCentering()
        if (container.isAttachedToWindow) container.post { ensureCreated() }
    }
}

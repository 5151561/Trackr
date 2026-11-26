package com.trackr.ui.screens.routeplan

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 根组合里那一个 [AmapMapSession]（不在 Activity 里时为 null，页面走矢量降级） */
val LocalAmapMapSession = staticCompositionLocalOf<AmapMapSession?> { null }

/**
 * 地图实例托管到 Activity 级会话：切走 tab 只把地图从窗口摘下，回来复用同一实例，
 * 不会重新初始化 GL / 重新拉瓦片 / 丢失相机位置（重构计划 §3.4）。
 *
 * 第 17 步从 `MainActivity` 原样搬来，由 `:composeApp` 包在 NavDisplay 外面；
 * 这里观察的 [LocalLifecycleOwner] 是 Activity 的，不是某个 entry 的（301c124：按 Home / 锁屏时暂停地图）。
 *
 * [enabled] 为 false（还没同意高德隐私说明）时不建会话，[LocalAmapMapSession] 提供 null，页面走矢量降级。
 * 组合结构（CompositionLocalProvider + content）始终不变，consent 翻转只重建地图会话，
 * 不会把里面一整棵导航子树销毁重建——否则 `rememberSaveable` 的返回栈会丢，同意后跳不到路线页。
 */
@Composable
fun ProvideAmapMapSession(enabled: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current

    val mapSession = remember(context, enabled) {
        if (enabled) (context as? Activity)?.let { AmapMapSession(it) } else null
    }
    DisposableEffect(mapSession) {
        onDispose { mapSession?.destroy() }
    }

    // 按 Home / 锁屏时暂停地图（内置定位 4s 一次 + GL 渲染），回到前台再恢复
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapSession) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> mapSession?.onHostPause()
                Lifecycle.Event.ON_RESUME -> mapSession?.onHostResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalAmapMapSession provides mapSession, content = content)
}

package com.trackr.ui.screens.me

import androidx.compose.runtime.Composable

/**
 * 「始终允许定位」申请入口（issue #49）。
 *
 * Android 10+ 单独申请 `ACCESS_BACKGROUND_LOCATION`（先确保有精确定位，再单独发起后台定位；
 * 系统不弹窗时回落打开应用设置页）；Android < 10 与 iOS 返回 null，页面不显示这一行。
 */
interface BackgroundLocationRequester {
    fun request()
}

@Composable
expect fun rememberBackgroundLocationRequester(onResult: () -> Unit): BackgroundLocationRequester?

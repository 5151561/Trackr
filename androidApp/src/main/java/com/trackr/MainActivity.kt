package com.trackr

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trackr.app.TrackrRoot
import com.trackr.core.navigation.deeplink.DeepLink
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.domain.platform.MapSdkInitializer
import com.trackr.core.domain.platform.TrackingController
import com.trackr.deeplink.DeepLinkIntents
import com.trackr.util.TransitCodeHelper
import org.koin.android.ext.android.get

/**
 * 应用唯一的 Activity：edge-to-edge、把 Intent 交给 [DeepLinkIntents] / `DeepLinkParser`（小组件、快捷方式、通知的
 * `trackr://` 链接，§3.6），其余都在 `:composeApp` 的 [TrackrRoot] 里。
 *
 * 导出的（桌面图标要用），所以链接可能来自别的 App：解析器只认 `trackr://` 已知的几种、校验 id，不转发 Intent。
 */
class MainActivity : ComponentActivity() {

    /** 待落栈的链接；[TrackrRoot] 落完清掉。 */
    @get:VisibleForTesting
    internal var pendingDeepLink by mutableStateOf<DeepLink?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // 页面跳转和乘车码都只在新 Intent 上处理；配置变更 / 进程重建恢复时不重放历史链接。
        handleIntent(intent, navigate = savedInstanceState == null)

        // 根 Composable（主题、底栏、导航）第 17 步搬进 :composeApp 的 TrackrRoot
        setContent {
            TrackrRoot(
                deepLink = pendingDeepLink,
                onDeepLinkConsumed = { pendingDeepLink = null },
                onAmapPrivacyAccepted = {
                    val keys = get<AmapKeyRepository>()
                    runCatching {
                        get<MapSdkInitializer>().initialize(keys.androidKey.value)
                    }
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, navigate = true)
    }

    override fun onStart() {
        super.onStart()
        get<TrackingController>().resumeInterrupted()
    }

    private fun handleIntent(intent: Intent?, navigate: Boolean) {
        when (val link = DeepLinkIntents.parse(intent)) {
            null -> return
            // 不入栈，只有新点击才拉起支付宝 / 云闪付乘车码。
            is DeepLink.TransitCode -> if (navigate) TransitCodeHelper.openTransitCode(this, link.provider)
            else -> if (navigate) pendingDeepLink = link
        }
        // 一次性消费：清掉 Intent 里的链接，杜绝重放
        DeepLinkIntents.consume(intent)
    }
}

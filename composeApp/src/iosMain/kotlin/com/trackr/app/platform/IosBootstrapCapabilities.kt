package com.trackr.app.platform

import com.trackr.core.data.repository.UserLocationStore
import com.trackr.core.domain.platform.*
import com.trackr.core.model.TransitCodeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.UIKit.*
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

object IosMonotonicClock : MonotonicClock {
    override fun elapsedMillis() = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
}

class IosUserLocationStore : UserLocationStore {
    private val location = MutableStateFlow<Pair<Double, Double>?>(null)
    override val userLocation = location.asStateFlow()
    override fun updateUserLocation(lat: Double, lng: Double) {
        if (lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) {
            location.value = lat to lng
        }
    }
}

object IosSystemSettingsLauncher : SystemSettingsLauncher {
    override fun openLocationSettings() = openAppSettings()
    override fun openAppSettings(): Boolean {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return false
        if (!UIApplication.sharedApplication.canOpenURL(url)) return false
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        return true
    }
}

object IosAppSigningInfo : AppSigningInfo {
    override val appId get() = NSBundle.mainBundle.bundleIdentifier.orEmpty()
    override fun certificateSha1(): String? = null
}

object IosTransitCodeLauncher : TransitCodeLauncher {
    private fun url(provider: TransitCodeProvider) = when (provider) {
        TransitCodeProvider.ALIPAY -> NSURL.URLWithString("alipays://platformapi/startapp?appId=200011235")
        TransitCodeProvider.UNIONPAY -> NSURL.URLWithString("upwallet://rn/rnhtmlridingcode")
        TransitCodeProvider.ASK_ALWAYS -> null
    }
    override fun isInstalled(provider: TransitCodeProvider) = url(provider)?.let {
        UIApplication.sharedApplication.canOpenURL(it)
    } ?: false
    override fun open(provider: TransitCodeProvider): Boolean {
        val url = url(provider) ?: return false
        if (!UIApplication.sharedApplication.canOpenURL(url)) return false
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>()) { opened ->
            if (!opened) showPlatformNotice("无法打开乘车码，请打开对应 App 出示乘车码。")
        }
        return true
    }
}

/**
 * 外部地图导航（站点页「导航到此站」）：iOS 没有高德 / 百度 / 腾讯那套调起，
 * [installedApps] 恒为空，ViewModel 直接走 [openSystem] 打开 Apple Maps（坐标本来就是 WGS-84，不用转）。
 */
object IosExternalMapNavigator : ExternalMapNavigator {
    override fun installedApps(): List<ExternalMapApp> = emptyList()

    override fun open(app: ExternalMapApp, wgsLat: Double, wgsLng: Double, name: String): Boolean = false

    override fun openSystem(wgsLat: Double, wgsLng: Double, name: String): Boolean {
        val url = NSURL.URLWithString("https://maps.apple.com/?daddr=$wgsLat,$wgsLng") ?: return false
        if (!UIApplication.sharedApplication.canOpenURL(url)) return false
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        return true
    }
}

internal fun showPlatformNotice(message: String) {
    val scene = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>().firstOrNull {
        it.activationState == UISceneActivationStateForegroundActive
    } ?: return
    var presenter = scene.windows.filterIsInstance<UIWindow>().firstOrNull { it.isKeyWindow() }?.rootViewController ?: return
    while (presenter.presentedViewController != null) presenter = presenter.presentedViewController!!
    if (presenter is UIAlertController) return
    val alert = UIAlertController.alertControllerWithTitle("追客", message, UIAlertControllerStyleAlert)
    alert.addAction(UIAlertAction.actionWithTitle("知道了", UIAlertActionStyleDefault, null))
    presenter.presentViewController(alert, animated = true, completion = null)
}

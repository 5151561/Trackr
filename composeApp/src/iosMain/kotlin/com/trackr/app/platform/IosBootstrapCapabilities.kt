package com.trackr.app.platform

import com.trackr.core.data.repository.UserLocationStore
import com.trackr.core.domain.platform.*
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.UserFix
import com.trackr.data.repository.IScheduledReminderRepository
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

/** Bootstrap only: never create a session that no worker will update. */
object IosUnavailableTrackingController : TrackingController {
    override fun start(target: TrackingTarget): Boolean {
        showUnavailable("iOS 追踪尚未接入，当前可查询实时到站信息和收藏线路。")
        return false
    }
    override fun stopTracking(sessionId: String?) = Unit
    override fun confirmBoarded(sessionId: String?) = Unit
    override fun chooseBoardedBus(busId: String?, sessionId: String?) = Unit
    override fun removeWatchLine(sessionId: String, lineId: String) = Unit
    override fun switchToNextLeg(sessionId: String?) = Unit
    override fun cycleThreshold(sessionId: String?) = Unit
    override fun updateUserFix(fix: UserFix) = Unit
}

/** Scheduling is explicitly unavailable until the iOS notification adapter is added. */
object IosUnavailableReminders : IScheduledReminderRepository, ReminderAlarms {
    override val unavailableReason = "iOS 定时提醒尚未接入，当前可使用公交查询和收藏"
    override val reminders = MutableStateFlow<List<ScheduledReminder>>(emptyList()).asStateFlow()
    override suspend fun saveReminder(reminder: ScheduledReminder): Unit = error(unavailableReason)
    override suspend fun deleteReminder(id: String): Unit = error(unavailableReason)
    override suspend fun toggleReminder(id: String, enabled: Boolean): Unit = error(unavailableReason)
    override fun getReminderById(id: String): ScheduledReminder? = null
    override fun schedule(reminder: ScheduledReminder): Unit = error(unavailableReason)
    override fun cancel(reminderId: String) = Unit
    override fun rescheduleAll(reminders: List<ScheduledReminder>) = Unit
    override fun canScheduleExact() = false
    override fun lastScheduledTriggerMillis(reminderId: String): Long? = null
}

object IosUnavailableTransitCode : TransitCodeLauncher {
    override fun open(provider: TransitCodeProvider): Boolean {
        showUnavailable("iOS 乘车码跳转尚未接入，请打开对应 App 出示乘车码。")
        return false
    }
    override fun isInstalled(provider: TransitCodeProvider) = false
}

private fun showUnavailable(message: String) {
    val scene = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>().firstOrNull {
        it.activationState == UISceneActivationStateForegroundActive
    } ?: return
    var presenter = scene.windows.filterIsInstance<UIWindow>().firstOrNull { it.isKeyWindow() }?.rootViewController ?: return
    while (presenter.presentedViewController != null) presenter = presenter.presentedViewController!!
    if (presenter is UIAlertController) return
    val alert = UIAlertController.alertControllerWithTitle("暂不可用", message, UIAlertControllerStyleAlert)
    alert.addAction(UIAlertAction.actionWithTitle("知道了", UIAlertActionStyleDefault, null))
    presenter.presentViewController(alert, animated = true, completion = null)
}

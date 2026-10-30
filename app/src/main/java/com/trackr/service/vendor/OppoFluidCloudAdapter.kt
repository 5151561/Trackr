package com.trackr.service.vendor

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object OppoFluidCloudAdapter {

    private const val TAG = "OppoFluidCloud"

    const val ACTION_FLUID_CLOUD_SETTINGS = "oplus.fluid.seeding.settings.main"
    const val ACTION_FLUID_CLOUD_SETTINGS_COMPONENT = "com.oplus.notificationmanager"
    const val ACTION_FLUID_CLOUD_SETTINGS_CLASS =
        "com.oplus.notificationmanager.fluidseedingsettings.activity.FluidSeedingSettingMainActivity"

    private val COLOR_OS_BRANDS = listOf("oppo", "oneplus", "realme")

    fun isColorOs(
        brand: String = Build.BRAND,
        manufacturer: String = Build.MANUFACTURER
    ): Boolean {
        val brandLower = brand.lowercase()
        val manufacturerLower = manufacturer.lowercase()
        return COLOR_OS_BRANDS.any { brandLower.contains(it) } ||
                COLOR_OS_BRANDS.any { manufacturerLower.contains(it) }
    }

    fun isFluidCloudSupported(): Boolean = LiveUpdatePromotion.isPromotionSupported()

    fun canShowFluidCloud(context: Context): Boolean = LiveUpdatePromotion.canPromote(context)

    fun openFluidCloudSettings(context: Context): Boolean {
        val byAction = Intent(ACTION_FLUID_CLOUD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, byAction)) return true

        val byComponent = Intent().apply {
            setClassName(
                ACTION_FLUID_CLOUD_SETTINGS_COMPONENT,
                ACTION_FLUID_CLOUD_SETTINGS_CLASS
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, byComponent)) return true

        return LiveUpdatePromotion.openPromotionSettings(context)
    }

    fun openPromotionSettings(context: Context): Boolean = LiveUpdatePromotion.openPromotionSettings(context)

    private fun startSafely(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start fluid cloud settings", e)
            false
        }
    }
}

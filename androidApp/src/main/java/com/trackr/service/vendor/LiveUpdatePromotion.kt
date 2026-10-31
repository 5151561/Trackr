package com.trackr.service.vendor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat

object LiveUpdatePromotion {

    private const val TAG = "LiveUpdatePromotion"

    fun isPromotionSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    fun canPromote(context: Context): Boolean {
        if (!isPromotionSupported()) return true
        return try {
            NotificationManagerCompat.from(context).canPostPromotedNotifications()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query promoted notification capability", e)
            true
        }
    }

    fun openPromotionSettings(context: Context): Boolean {
        if (isPromotionSupported()) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (startSafely(context, intent)) return true
        }
        return openAppNotificationSettings(context)
    }

    fun openAppNotificationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, intent)) return true
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startSafely(context, details)
    }

    private fun startSafely(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start settings intent ${intent.action}", e)
            false
        }
    }
}

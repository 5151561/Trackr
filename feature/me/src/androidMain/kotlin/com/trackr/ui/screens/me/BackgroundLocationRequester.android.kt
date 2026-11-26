package com.trackr.ui.screens.me

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** 系统静默秒拒（没弹权限页 / 永久拒绝）的判定阈值：短于这个时长就回落打开应用设置页 */
private const val SILENT_DENY_WINDOW_MILLIS = 350L

@Composable
actual fun rememberBackgroundLocationRequester(onResult: () -> Unit): BackgroundLocationRequester? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    var bgLaunchedAt by remember { mutableLongStateOf(0L) }
    var fineLaunchedAt by remember { mutableLongStateOf(0L) }

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && SystemClock.elapsedRealtime() - bgLaunchedAt < SILENT_DENY_WINDOW_MILLIS) {
            openAppSettings(context)
        }
        currentOnResult()
    }

    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            bgLaunchedAt = SystemClock.elapsedRealtime()
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            if (SystemClock.elapsedRealtime() - fineLaunchedAt < SILENT_DENY_WINDOW_MILLIS) {
                openAppSettings(context)
            }
            currentOnResult()
        }
    }

    return remember(context, bgLauncher, fineLauncher) {
        object : BackgroundLocationRequester {
            override fun request() {
                val hasFine = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (hasFine) {
                    bgLaunchedAt = SystemClock.elapsedRealtime()
                    bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    fineLaunchedAt = SystemClock.elapsedRealtime()
                    fineLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

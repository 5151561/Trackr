package com.trackr.ui.screens.more

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.data.remote.update.IUpdateChecker
import com.trackr.domain.model.AppUpdateInfo
import com.trackr.domain.model.UpdateCheckResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 关于页版本更新 UI 状态
 */
sealed interface AboutUpdateUiState {
    data object Idle : AboutUpdateUiState
    data object Checking : AboutUpdateUiState
    data class HasUpdate(val updateInfo: AppUpdateInfo, val isForce: Boolean) : AboutUpdateUiState
    data class UpToDate(val currentVersionName: String) : AboutUpdateUiState
    data class Error(val message: String) : AboutUpdateUiState
}

class AboutViewModel(
    application: Application,
    private val updateChecker: IUpdateChecker,
    private val versionProvider: () -> Pair<Int, String> = {
        getDefaultAppVersion(application)
    }
) : AndroidViewModel(application) {

    private val _updateState = MutableStateFlow<AboutUpdateUiState>(AboutUpdateUiState.Idle)
    val updateState: StateFlow<AboutUpdateUiState> = _updateState.asStateFlow()

    fun checkForUpdates() {
        if (_updateState.value is AboutUpdateUiState.Checking) return
        _updateState.value = AboutUpdateUiState.Checking

        viewModelScope.launch {
            val (currentVersionCode, currentVersionName) = versionProvider()
            val result = updateChecker.checkForUpdates(
                currentVersionCode = currentVersionCode,
                currentVersionName = currentVersionName
            )

            _updateState.value = when (result) {
                is UpdateCheckResult.HasUpdate ->
                    AboutUpdateUiState.HasUpdate(result.updateInfo, result.isForceUpdate)
                is UpdateCheckResult.UpToDate ->
                    AboutUpdateUiState.UpToDate(result.currentVersionName)
                is UpdateCheckResult.Error ->
                    AboutUpdateUiState.Error(result.message)
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateState.value = AboutUpdateUiState.Idle
    }

    companion object {
        fun getDefaultAppVersion(context: Context): Pair<Int, String> {
            return runCatching {
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode
                }
                val versionName = packageInfo.versionName ?: "0.0.1"
                versionCode to versionName
            }.getOrDefault(1 to "0.0.1")
        }
    }
}

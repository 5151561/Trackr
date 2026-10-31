package com.trackr.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 远程版本更新信息
 */
@Serializable
data class AppUpdateInfo(
    @SerialName("versionCode")
    val versionCode: Int,
    @SerialName("versionName")
    val versionName: String,
    @SerialName("minSupportedVersionCode")
    val minSupportedVersionCode: Int = 1,
    @SerialName("changelog")
    val changelog: String,
    @SerialName("downloadUrl")
    val downloadUrl: String,
    @SerialName("publishTime")
    val publishTime: String? = null,
    @SerialName("forceUpdate")
    val forceUpdate: Boolean = false
)

/**
 * 版本更新检测结果
 */
sealed interface UpdateCheckResult {
    /**
     * 发现新版本
     */
    data class HasUpdate(
        val updateInfo: AppUpdateInfo,
        val isForceUpdate: Boolean
    ) : UpdateCheckResult

    /**
     * 当前已是最新版本
     */
    data class UpToDate(
        val currentVersionName: String
    ) : UpdateCheckResult

    /**
     * 检查更新失败
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : UpdateCheckResult
}

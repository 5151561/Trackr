package com.trackr.core.model

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
    /** 按 ABI 细分的安装包地址，键与 `Build.SUPPORTED_ABIS` 里的值一致，如 "arm64-v8a" */
    @SerialName("downloadUrls")
    val downloadUrls: Map<String, String> = emptyMap(),
    @SerialName("publishTime")
    val publishTime: String? = null,
    @SerialName("forceUpdate")
    val forceUpdate: Boolean = false
) {
    /**
     * 按设备 ABI 优先级选安装包地址；没有匹配项时回退到 [downloadUrl]。
     */
    fun pickDownloadUrl(supportedAbis: List<String>): String {
        for (abi in supportedAbis) {
            val url = downloadUrls[abi]?.trim().orEmpty()
            if (url.isNotEmpty()) return url
        }
        return downloadUrl
    }
}

/**
 * 应用内下载安装的进度（只有 Android 有）
 */
sealed interface AppUpdateInstallState {
    /** 没有在下载，等用户点「下载并安装」 */
    data object Idle : AppUpdateInstallState

    /**
     * 正在下载安装包。
     * @param downloadedBytes 已下载字节数
     * @param totalBytes 安装包总字节数；服务器没给时为 0
     */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : AppUpdateInstallState

    /** 安装包已下载好，但系统还没允许本应用安装应用（Android 8+ 的「安装未知应用」） */
    data object AwaitingPermission : AppUpdateInstallState

    /** 已调起系统安装界面，等用户在那里确认 */
    data object Launched : AppUpdateInstallState

    /** 下载或安装失败，[message] 直接展示给用户 */
    data class Failed(val message: String) : AppUpdateInstallState
}

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

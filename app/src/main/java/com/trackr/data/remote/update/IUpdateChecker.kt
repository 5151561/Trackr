package com.trackr.data.remote.update

import com.trackr.domain.model.UpdateCheckResult

/**
 * 应用版本更新检测接口
 */
interface IUpdateChecker {
    /**
     * 检查是否有新版本
     * @param currentVersionCode 当前安装版本的 versionCode
     * @param currentVersionName 当前安装版本的 versionName
     */
    suspend fun checkForUpdates(
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheckResult
}

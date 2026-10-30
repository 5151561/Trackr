package com.trackr.data.remote.update

import com.trackr.domain.model.AppUpdateInfo
import com.trackr.domain.model.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 基于 GitHub / CDN 的应用更新检测器
 */
class GithubUpdateChecker(
    private val httpClient: OkHttpClient,
    private val endpointUrls: List<String> = DEFAULT_ENDPOINTS,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
) : IUpdateChecker {

    companion object {
        val DEFAULT_ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/5151561/Trackr/update/update.json",
            "https://fastly.jsdelivr.net/gh/5151561/Trackr@update/update.json",
            "https://cdn.jsdelivr.net/gh/5151561/Trackr@update/update.json"
        )
    }

    override suspend fun checkForUpdates(
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        var lastException: Throwable? = null

        for (url in endpointUrls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Trackr-Android-App/$currentVersionName")
                    .header("Cache-Control", "no-cache")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastException = IOException("HTTP ${response.code}: ${response.message}")
                        return@use
                    }

                    val bodyString = response.body.string()
                    if (bodyString.isNullOrBlank()) {
                        lastException = IOException("Empty response body from $url")
                        return@use
                    }

                    val updateInfo = json.decodeFromString<AppUpdateInfo>(bodyString)
                    val hasUpdate = updateInfo.versionCode > currentVersionCode
                    val isForce = updateInfo.forceUpdate || currentVersionCode < updateInfo.minSupportedVersionCode

                    return@withContext if (hasUpdate) {
                        UpdateCheckResult.HasUpdate(
                            updateInfo = updateInfo,
                            isForceUpdate = isForce
                        )
                    } else {
                        UpdateCheckResult.UpToDate(currentVersionName)
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        UpdateCheckResult.Error(
            message = lastException?.localizedMessage ?: "未能连接到更新服务器，请检查网络后重试",
            cause = lastException
        )
    }
}

package com.trackr.data.remote.update

import com.trackr.domain.model.AppUpdateInfo
import com.trackr.domain.model.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

        /** 更新包下载地址允许的域名白名单（精确匹配，不含子域名） */
        val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "raw.githubusercontent.com",
            "cdn.jsdelivr.net",
            "fastly.jsdelivr.net"
        )

        /**
         * 校验下载地址：必须是 https，且 host 在 [ALLOWED_DOWNLOAD_HOSTS] 白名单内
         */
        fun isAllowedDownloadUrl(url: String): Boolean {
            val httpUrl = url.trim().toHttpUrlOrNull() ?: return false
            return httpUrl.isHttps && httpUrl.host.lowercase() in ALLOWED_DOWNLOAD_HOSTS
        }
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
                    if (!isAllowedDownloadUrl(updateInfo.downloadUrl)) {
                        // 下载地址不合规（非 https 或域名不在白名单）视为该源出错，继续尝试下一个源
                        lastException = IOException("更新下载地址不合规：${updateInfo.downloadUrl}")
                        return@use
                    }
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

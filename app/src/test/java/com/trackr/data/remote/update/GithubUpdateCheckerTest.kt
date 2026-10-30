package com.trackr.data.remote.update

import com.trackr.domain.model.UpdateCheckResult
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class GithubUpdateCheckerTest {

    @Test
    fun checkForUpdates_whenNewVersionAvailable_returnsHasUpdate() = runTest {
        val jsonPayload = """
            {
                "versionCode": 3,
                "versionName": "0.0.3",
                "minSupportedVersionCode": 1,
                "changelog": "新特性 1\n新特性 2",
                "downloadUrl": "https://github.com/5151561/Trackr/releases/tag/v0.0.3",
                "publishTime": "2026-10-30",
                "forceUpdate": false
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonPayload.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val checker = GithubUpdateChecker(httpClient = client, endpointUrls = listOf("https://example.com/update.json"))
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")

        assertTrue(result is UpdateCheckResult.HasUpdate)
        val hasUpdate = result as UpdateCheckResult.HasUpdate
        assertEquals(3, hasUpdate.updateInfo.versionCode)
        assertEquals("0.0.3", hasUpdate.updateInfo.versionName)
        assertEquals("新特性 1\n新特性 2", hasUpdate.updateInfo.changelog)
        assertEquals("https://github.com/5151561/Trackr/releases/tag/v0.0.3", hasUpdate.updateInfo.downloadUrl)
        assertFalse(hasUpdate.isForceUpdate)
    }

    @Test
    fun checkForUpdates_whenAlreadyLatestVersion_returnsUpToDate() = runTest {
        val jsonPayload = """
            {
                "versionCode": 2,
                "versionName": "0.0.2",
                "minSupportedVersionCode": 1,
                "changelog": "更新说明",
                "downloadUrl": "https://github.com/5151561/Trackr/releases",
                "publishTime": "2026-10-30",
                "forceUpdate": false
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonPayload.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val checker = GithubUpdateChecker(httpClient = client, endpointUrls = listOf("https://example.com/update.json"))
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")

        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("0.0.2", (result as UpdateCheckResult.UpToDate).currentVersionName)
    }

    @Test
    fun checkForUpdates_whenVersionLowerThanMinSupported_marksForceUpdate() = runTest {
        val jsonPayload = """
            {
                "versionCode": 10,
                "versionName": "1.0.0",
                "minSupportedVersionCode": 5,
                "changelog": "大版本升级",
                "downloadUrl": "https://github.com/5151561/Trackr/releases",
                "forceUpdate": false
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(jsonPayload.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val checker = GithubUpdateChecker(httpClient = client, endpointUrls = listOf("https://example.com/update.json"))
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")

        assertTrue(result is UpdateCheckResult.HasUpdate)
        assertTrue((result as UpdateCheckResult.HasUpdate).isForceUpdate)
    }

    @Test
    fun checkForUpdates_whenFirstEndpointFails_fallsBackToNextEndpoint() = runTest {
        val jsonPayload = """
            {
                "versionCode": 3,
                "versionName": "0.0.3",
                "changelog": "Fallback successful",
                "downloadUrl": "https://github.com/5151561/Trackr/releases"
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestUrl = chain.request().url.toString()
                if (requestUrl.contains("primary.com")) {
                    throw IOException("Network timeout on primary")
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(jsonPayload.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val checker = GithubUpdateChecker(
            httpClient = client,
            endpointUrls = listOf("https://primary.com/update.json", "https://cdn.example.com/update.json")
        )
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")

        assertTrue(result is UpdateCheckResult.HasUpdate)
        assertEquals("0.0.3", (result as UpdateCheckResult.HasUpdate).updateInfo.versionName)
    }

    @Test
    fun checkForUpdates_whenAllEndpointsFail_returnsError() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("Unable to resolve host")
            }
            .build()

        val checker = GithubUpdateChecker(
            httpClient = client,
            endpointUrls = listOf("https://bad1.com/update.json", "https://bad2.com/update.json")
        )
        val result = checker.checkForUpdates(currentVersionCode = 2, currentVersionName = "0.0.2")

        assertTrue(result is UpdateCheckResult.Error)
        assertEquals("Unable to resolve host", (result as UpdateCheckResult.Error).message)
    }
}

package com.trackr.data.remote.update

import com.trackr.core.model.AppUpdateInfo
import com.trackr.core.model.AppUpdateInstallState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidAppUpdateInstallerTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun downloadAndInstall_whenUrlNotTrusted_failsWithoutRequest() = runBlocking {
        var requests = 0
        val installer = AndroidAppUpdateInstaller(
            context = context,
            httpClient = HttpClient(MockEngine { _ ->
                requests++
                respond("", HttpStatusCode.OK)
            })
        )

        val result = installer.downloadAndInstall(updateInfo("https://evil.example.com/trackr.apk"))

        assertTrue(result is AppUpdateInstallState.Failed)
        assertEquals(0, requests)
    }

    @Test
    fun downloadAndInstall_whenUrlIsReleasePage_failsWithoutDownloadingHtml() = runBlocking {
        var requests = 0
        val installer = AndroidAppUpdateInstaller(
            context = context,
            httpClient = HttpClient(MockEngine { _ ->
                requests++
                respond("", HttpStatusCode.OK)
            })
        )

        // update.json 没带 tag 时 downloadUrl 是发布页，不是 APK
        val result = installer.downloadAndInstall(updateInfo("https://github.com/5151561/Trackr/releases"))

        assertTrue(result is AppUpdateInstallState.Failed)
        assertTrue((result as AppUpdateInstallState.Failed).message.contains("没有可直接安装的安装包"))
        assertEquals(0, requests)
    }

    @Test
    fun downloadAndInstall_whenHttpError_reportsStatus() = runBlocking {
        val installer = AndroidAppUpdateInstaller(
            context = context,
            httpClient = HttpClient(MockEngine { _ -> respond("", HttpStatusCode.NotFound) })
        )

        val result = installer.downloadAndInstall(updateInfo(APK_URL))

        assertTrue(result is AppUpdateInstallState.Failed)
        assertTrue((result as AppUpdateInstallState.Failed).message.contains("404"))
    }

    @Test
    fun downloadAndInstall_whenFileIsNotAnApk_failsVerification() = runBlocking {
        val installer = AndroidAppUpdateInstaller(
            context = context,
            httpClient = HttpClient(MockEngine { _ ->
                respond(
                    ByteArray(1024) { 7 },
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentLength, "1024")
                )
            })
        )

        val result = installer.downloadAndInstall(updateInfo(APK_URL))

        assertTrue(result is AppUpdateInstallState.Failed)
        assertTrue((result as AppUpdateInstallState.Failed).message.contains("安装包不完整"))
    }

    @Test
    fun retryPendingInstall_whenNothingDownloaded_fails() {
        val installer = AndroidAppUpdateInstaller(context)

        val result = installer.retryPendingInstall()

        assertTrue(result is AppUpdateInstallState.Failed)
    }

    @Test
    fun discardPending_isSafeWhenNothingDownloaded() {
        val installer = AndroidAppUpdateInstaller(context)

        installer.discardPending()
        installer.discardPending()
    }

    private fun updateInfo(url: String) = AppUpdateInfo(
        versionCode = 6,
        versionName = "0.1.2",
        changelog = "修了下载",
        downloadUrl = url
    )

    private companion object {
        const val APK_URL = "https://github.com/5151561/Trackr/releases/download/v0.1.2/trackr-0.1.2-arm64-v8a.apk"
    }
}

package com.trackr.data.remote.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.trackr.core.domain.platform.AppUpdateInstaller
import com.trackr.core.model.AppUpdateInfo
import com.trackr.core.model.AppUpdateInstallState
import com.trackr.core.network.remote.common.createTrackrDownloadHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 应用内更新：把安装包下到应用缓存，校验包名 / 版本 / 签名，再交给系统安装器。
 *
 * 安装包下载到 `cacheDir/updates/trackr-<versionCode>.apk`，下了一半的文件用完就删。
 * 校验签名是为了在装错的包（比如自己签的 debug 包）时给一句人话，而不是让系统弹「应用未安装」。
 *
 * 对应接口见 [AppUpdateInstaller]；「安装未知应用」的授权只能用系统设置页，所以分成
 * [downloadAndInstall] / [retryPendingInstall] 两步。
 */
class AndroidAppUpdateInstaller(
    context: Context,
    private val httpClient: HttpClient = createTrackrDownloadHttpClient()
) : AppUpdateInstaller {

    private val appContext: Context = context.applicationContext ?: context
    private val packageManager: PackageManager = appContext.packageManager

    /** 已经下载好、还没装上的包；从设置授权回来靠它接着装 */
    @Volatile
    private var pendingFile: File? = null

    override suspend fun downloadAndInstall(
        updateInfo: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): AppUpdateInstallState {
        if (!GithubUpdateChecker.isAllowedDownloadUrl(updateInfo.downloadUrl)) {
            return AppUpdateInstallState.Failed("下载地址不受信任，请到 GitHub 手动下载")
        }
        // update.json 没带 tag 时 downloadUrl 是发布页而不是 APK；别把网页当安装包下下来
        val path = updateInfo.downloadUrl.substringBefore('?').substringBefore('#')
        if (!path.endsWith(".apk", ignoreCase = true)) {
            return AppUpdateInstallState.Failed("这个版本没有可直接安装的安装包，请到 GitHub 下载")
        }

        val file = try {
            withContext(Dispatchers.IO) { download(updateInfo, onProgress) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return AppUpdateInstallState.Failed(e.localizedMessage ?: "下载失败，请检查网络后重试")
        }

        pendingFile = file
        val problem = withContext(Dispatchers.IO) { verifyPackage(file) }
        if (problem != null) {
            file.delete()
            pendingFile = null
            return AppUpdateInstallState.Failed(problem)
        }
        return tryInstall(file)
    }

    override fun retryPendingInstall(): AppUpdateInstallState {
        val file = pendingFile?.takeIf { it.isFile } ?: return AppUpdateInstallState.Failed("安装包已失效，请重新下载")
        return tryInstall(file)
    }

    override fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    override fun discardPending() {
        pendingFile?.delete()
        pendingFile = null
    }

    /** 关掉下载用的 HttpClient（Koin 关闭时调用） */
    fun close() {
        discardPending()
        httpClient.close()
    }

    /**
     * 下载到缓存目录。先写 `.part`，下完再改名，中途取消不会留下半个 APK。
     */
    private suspend fun download(
        updateInfo: AppUpdateInfo,
        onProgress: (Long, Long) -> Unit
    ): File {
        val dir = File(appContext.cacheDir, UPDATES_DIR).apply { mkdirs() }
        // 只清自己的目录：旧版本的包留着没用，还占几十兆
        dir.listFiles()?.forEach { it.delete() }

        val target = File(dir, "trackr-${updateInfo.versionCode}.apk")
        val part = File(dir, "trackr-${updateInfo.versionCode}.apk.part")
        part.delete()

        try {
            httpClient.prepareGet(updateInfo.downloadUrl) {
                header(HttpHeaders.UserAgent, "Trackr-Android-App/${appVersionName()}")
                header(HttpHeaders.Accept, APK_MIME_TYPE)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw IOException("下载失败（HTTP ${response.status.value}）")
                }
                val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
                val channel = response.bodyAsChannel()
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var downloaded = 0L
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        }

        if (part.length() <= 0L) throw IOException("下载的安装包是空的，请重试")
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("保存安装包失败，请检查存储空间")
        }
        return target
    }

    /**
     * 校验下载到的安装包：是追客、版本比现在新、签名和现在装的一致。
     * 没问题返回 null，有问题返回给用户看的一句话。
     */
    private fun verifyPackage(file: File): String? {
        val archive = archiveInfo(file) ?: return "安装包不完整，请重新下载"
        if (archive.packageName != appContext.packageName) {
            return "下载到的不是追客的安装包，已停止安装"
        }

        val installed = runCatching { installedInfo() }.getOrNull() ?: return "读不到当前版本，请重试"
        val archiveVersion = versionCodeOf(archive)
        if (archiveVersion <= versionCodeOf(installed)) {
            return "下载的安装包版本不正确，请重新检查更新"
        }

        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installed)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty() || archiveSigners.intersect(installedSigners).isEmpty()) {
            return "安装包签名与当前版本不一致，请到 GitHub 下载官方安装包"
        }
        return null
    }

    private fun tryInstall(file: File): AppUpdateInstallState {
        if (!packageManager.canRequestPackageInstalls()) {
            // 不自己跳设置：由页面显示说明和「去设置」按钮，用户回来再装
            return AppUpdateInstallState.AwaitingPermission
        }
        return try {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            AppUpdateInstallState.Launched
        } catch (e: ActivityNotFoundException) {
            AppUpdateInstallState.Failed("没有找到系统安装程序")
        } catch (e: Exception) {
            AppUpdateInstallState.Failed(e.localizedMessage ?: "无法打开系统安装程序")
        }
    }

    private fun installedInfo(): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES)
        }

    private fun archiveInfo(file: File): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /** 签名证书的 SHA-256 指纹；比较两个包的签名是否来自同一把钥匙 */
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }.orEmpty()
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }

    private fun appVersionName(): String = runCatching {
        installedInfo().versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }

    private companion object {
        const val UPDATES_DIR = "updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val BUFFER_BYTES = 64 * 1024
    }
}

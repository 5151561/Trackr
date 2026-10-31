package com.trackr.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

/**
 * 签名与应用包名信息提取工具
 * 用于动态获取当前运行环境的 PackageName 与 SHA-1 安全码，
 * 方便用户在自编译或不同签名打包时，一键复制真实证书指纹向高德开放平台申请 Android Key。
 */
object SignatureUtils {

    /**
     * 获取当前 App 的实际运行包名
     */
    fun getPackageName(context: Context): String = context.packageName

    /**
     * 获取当前 App 签名的 SHA-1 安全码（格式如：AA:BB:CC:...）
     */
    fun getSigningCertificateSha1(context: Context): String {
        return runCatching {
            val pm = context.packageManager
            val packageName = context.packageName

            val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo ?: return@runCatching ""
                if (signingInfo.hasMultipleSigners()) {
                    // 多签名者场景无签名历史，取 APK 内容签名的第一个
                    signingInfo.apkContentsSigners?.firstOrNull()
                } else {
                    selectCurrentFromHistory(signingInfo.signingCertificateHistory)
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures?.firstOrNull()
            }

            if (certificate == null) return@runCatching ""
            sha1Fingerprint(certificate.toByteArray())
        }.getOrDefault("")
    }

    /**
     * 从签名证书历史中选出当前签名证书。
     * 官方文档（SigningInfo#getSigningCertificateHistory）：按轮换顺序返回，
     * 原始证书位于下标 0，当前证书位于最后一个下标。
     */
    internal fun selectCurrentFromHistory(history: Array<Signature>?): Signature? = history?.lastOrNull()

    /**
     * 计算证书字节的 SHA-1 指纹，格式如 AA:BB:CC:...
     */
    internal fun sha1Fingerprint(certBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(certBytes)
        return digest.joinToString(":") { "%02X".format(Locale.ROOT, it) }
    }
}

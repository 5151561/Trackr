package com.trackr.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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

            val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo ?: return@runCatching ""
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (certificates.isNullOrEmpty()) return@runCatching ""

            val certBytes = certificates[0].toByteArray()
            val md = MessageDigest.getInstance("SHA-1")
            val digest = md.digest(certBytes)

            val hexString = StringBuilder()
            for (i in digest.indices) {
                val hex = Integer.toHexString(0xFF and digest[i].toInt()).uppercase(Locale.ROOT)
                if (hex.length == 1) {
                    hexString.append("0")
                }
                hexString.append(hex)
                if (i < digest.size - 1) {
                    hexString.append(":")
                }
            }
            hexString.toString()
        }.getOrDefault("")
    }
}

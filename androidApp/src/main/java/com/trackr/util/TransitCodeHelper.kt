package com.trackr.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.trackr.domain.model.TransitCodeProvider

/**
 * 乘车码唤醒与应用跳转工具类（支持支付宝乘车码与云闪付乘车码）
 */
object TransitCodeHelper {

    const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
    const val PACKAGE_UNIONPAY = "com.unionpay"

    // 支付宝乘车码通用 Scheme（系统级秒开）
    const val SCHEME_ALIPAY_TRANSIT = "alipays://platformapi/startapp?appId=200011235"

    // 云闪付全国通用乘车码 Scheme（基于全国通用版 rnhtmlridingcode 路由，避免地区限定）
    const val SCHEME_UNIONPAY_TRANSIT = "upwallet://rn/rnhtmlridingcode"

    /**
     * 根据渠道打开乘车码
     */
    fun openTransitCode(context: Context, provider: TransitCodeProvider): Boolean {
        return when (provider) {
            TransitCodeProvider.ALIPAY -> openAlipayTransitCode(context)
            TransitCodeProvider.UNIONPAY -> openUnionPayTransitCode(context)
            TransitCodeProvider.ASK_ALWAYS -> false
        }
    }

    /**
     * 唤起支付宝乘车码（系统级支持全局 Scheme 直达）
     */
    fun openAlipayTransitCode(context: Context): Boolean {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME_ALIPAY_TRANSIT)).apply {
                setPackage(PACKAGE_ALIPAY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (_: Exception) {
            // 降级尝试：不限 package 直接打开
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME_ALIPAY_TRANSIT)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                return true
            } catch (_: Exception) {
                // 再次降级：直接打开支付宝 App 主页
                val launchIntent = context.packageManager.getLaunchIntentForPackage(PACKAGE_ALIPAY)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    Toast.makeText(context, "已打开支付宝，请在首页使用乘车码", Toast.LENGTH_SHORT).show()
                    return true
                } else {
                    Toast.makeText(context, "未安装支付宝，无法打开乘车码", Toast.LENGTH_SHORT).show()
                    return false
                }
            }
        }
    }

    /**
     * 唤起云闪付乘车码（全国通用版直达）
     */
    fun openUnionPayTransitCode(context: Context): Boolean {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME_UNIONPAY_TRANSIT)).apply {
                setPackage(PACKAGE_UNIONPAY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (_: Exception) {
            // 降级尝试：不限定 package 直接调用系统解析
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME_UNIONPAY_TRANSIT)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                return true
            } catch (_: Exception) {
                // 降级尝试：若 Scheme 无法直接拉起，尝试直接打开云闪付 App
                val launchIntent = context.packageManager.getLaunchIntentForPackage(PACKAGE_UNIONPAY)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    Toast.makeText(context, "已打开云闪付，请在首页使用乘车码", Toast.LENGTH_SHORT).show()
                    return true
                } else {
                    Toast.makeText(context, "未安装云闪付客户端，无法打开乘车码", Toast.LENGTH_SHORT).show()
                    return false
                }
            }
        }
    }

    /**
     * 检查是否安装了支付宝
     */
    fun isAlipayInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PACKAGE_ALIPAY, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 检查是否安装了云闪付
     */
    fun isUnionPayInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PACKAGE_UNIONPAY, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

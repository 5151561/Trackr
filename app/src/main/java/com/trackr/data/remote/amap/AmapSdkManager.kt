package com.trackr.data.remote.amap

import android.content.Context
import android.util.Log
import com.amap.api.services.core.ServiceSettings
import com.trackr.BuildConfig

/**
 * 高德轻量搜索 SDK 管理器
 * 负责合规隐私初始化与动态 Key 注入管理。
 * 遵循严格的零密钥泄漏与解耦规范：
 * 绝不在源码中硬编码任何 Key，支持运行时用户自备 Key 或由 local.properties / 环境变量注入。
 */
object AmapSdkManager {
    private const val TAG = "AmapSdkManager"

    @Volatile
    private var isPrivacyInitialized = false

    /**
     * 更新高德搜索 SDK 隐私合规状态（符合工信部合规要求）
     */
    fun ensurePrivacyCompliance(context: Context) {
        if (!isPrivacyInitialized) {
            synchronized(this) {
                if (!isPrivacyInitialized) {
                    try {
                        ServiceSettings.updatePrivacyShow(context.applicationContext, true, true)
                        ServiceSettings.updatePrivacyAgree(context.applicationContext, true)
                        isPrivacyInitialized = true
                        Log.d(TAG, "AMap ServiceSettings privacy compliance initialized")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to initialize AMap privacy compliance", e)
                    }
                }
            }
        }
    }

    /**
     * 获取当前生效的高德 Android 平台 Key。
     * 优先级：用户界面自定义配置 > BuildConfig.AMAP_ANDROID_KEY > 空
     */
    fun getEffectiveApiKey(customKey: String?): String {
        val userKey = customKey?.trim().orEmpty()
        if (userKey.isNotEmpty()) {
            return userKey
        }
        val buildKey = BuildConfig.AMAP_ANDROID_KEY.trim()
        if (buildKey.isNotEmpty()) {
            return buildKey
        }
        return ""
    }

    /**
     * 动态设置高德 SDK 的 API Key
     */
    fun applyApiKey(context: Context, customKey: String?): Boolean {
        ensurePrivacyCompliance(context)
        val effectiveKey = getEffectiveApiKey(customKey)
        return if (effectiveKey.isNotEmpty()) {
            try {
                ServiceSettings.getInstance().setApiKey(effectiveKey)
                Log.d(TAG, "AMap ServiceSettings apiKey updated successfully")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to set AMap apiKey", e)
                false
            }
        } else {
            Log.w(TAG, "No valid AMap apiKey available")
            false
        }
    }
}

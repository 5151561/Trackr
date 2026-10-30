package com.trackr.data.remote.amap

import android.content.Context
import android.content.SharedPreferences

/**
 * 高德 Key 的本地持久化（两个独立槽位，避免 Android Key 与 Web 服务 Key 混用）。
 *
 * - [KEY_ANDROID]：地图渲染 / 搜索 / 定位 SDK 使用，必须与包名 + 签名 SHA-1 绑定；
 * - [KEY_WEB]：restapi.amap.com 的 HTTP 接口使用，不绑定包名。
 */
object AmapKeyStore {
    const val PREFS_NAME = "trackr_prefs"

    const val KEY_ANDROID = "custom_amap_android_key"

    /** 历史遗留槽位，早期版本把 Web 服务 Key 存在这里 */
    const val KEY_WEB = "custom_amap_web_key"

    fun prefs(context: Context): SharedPreferences? =
        runCatching { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()

    fun readExplicitAndroidKey(context: Context): String? =
        prefs(context)?.getString(KEY_ANDROID, null)?.trim()?.ifBlank { null }

    /**
     * 读取 Android 平台 Key。
     *
     * 兼容旧版本：早期只提供一个输入框，用户填的 Key 存在 [KEY_WEB] 槽位里。
     * 当新版 Android 槽位为空时回落到该历史值，保证升级后不会突然“丢 Key”。
     */
    fun readAndroidKey(context: Context): String? =
        readExplicitAndroidKey(context) ?: readWebKey(context)

    fun readWebKey(context: Context): String? =
        prefs(context)?.getString(KEY_WEB, null)?.trim()?.ifBlank { null }

    fun writeAndroidKey(context: Context, key: String?) {
        prefs(context)?.edit()?.putString(KEY_ANDROID, key?.trim()?.ifBlank { null })?.apply()
    }

    fun writeWebKey(context: Context, key: String?) {
        prefs(context)?.edit()?.putString(KEY_WEB, key?.trim()?.ifBlank { null })?.apply()
    }
}

package com.trackr.data.remote.amap

import android.content.Context
import android.content.SharedPreferences

/**
 * 高德 Key 的本地持久化（两个独立槽位，严格分槽，互不回落）。
 *
 * - [KEY_ANDROID]：地图渲染 / 搜索 / 定位 SDK 使用，必须与包名 + 签名 SHA-1 绑定；
 * - [KEY_WEB]：restapi.amap.com 的 HTTP 接口使用，不绑定包名。
 *
 * 关于旧版本单输入框留下的值（均存放在 [KEY_WEB]）：
 * - f20cce0 时期输入框标注为「Web 服务 Key」，只用于 restapi；
 * - 04dee2a ~ 63669e1 时期输入框改标「Android Key」，同一个值同时喂给 SDK 与 restapi；
 * - c73dd94 起 [KEY_WEB] 成为 Web 服务 Key 的正式槽位。
 * 仅凭存储内容无法区分这个值到底是哪一类 Key（这些版本均未打发布 tag），
 * 因此不做自动搬迁：值保留在 Web 槽位，Android 槽位为空时回落 BuildConfig 内置 Key，
 * 不再拿 Web 槽位的值去喂 SDK（否则只填了 Web Key 的用户会"SDK 必败 + Web 重试"）。
 */
object AmapKeyStore {
    const val PREFS_NAME = "trackr_prefs"

    const val KEY_ANDROID = "custom_amap_android_key"

    /** Web 服务 Key 槽位（历史上也是单输入框时代唯一的槽位） */
    const val KEY_WEB = "custom_amap_web_key"

    fun prefs(context: Context): SharedPreferences? =
        runCatching { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()

    /** 读取 Android 平台 Key（只读 Android 槽位，不回落 Web 槽位） */
    fun readAndroidKey(context: Context): String? = readAndroidKey(prefs(context))

    fun readAndroidKey(prefs: SharedPreferences?): String? =
        prefs?.getString(KEY_ANDROID, null)?.trim()?.ifBlank { null }

    fun readWebKey(context: Context): String? = readWebKey(prefs(context))

    fun readWebKey(prefs: SharedPreferences?): String? =
        prefs?.getString(KEY_WEB, null)?.trim()?.ifBlank { null }

    fun writeAndroidKey(context: Context, key: String?) {
        writeAndroidKey(prefs(context), key)
    }

    fun writeAndroidKey(prefs: SharedPreferences?, key: String?) {
        prefs?.edit()?.putString(KEY_ANDROID, key?.trim()?.ifBlank { null })?.apply()
    }

    fun writeWebKey(context: Context, key: String?) {
        writeWebKey(prefs(context), key)
    }

    fun writeWebKey(prefs: SharedPreferences?, key: String?) {
        prefs?.edit()?.putString(KEY_WEB, key?.trim()?.ifBlank { null })?.apply()
    }
}

package com.trackr.data.remote.amap

import android.content.Context
import android.content.SharedPreferences

/**
 * 高德 Key 的本地持久化（两个独立槽位，严格分槽，互不回落）。
 *
 * - [KEY_ANDROID]：地图渲染 / 搜索 / 定位 SDK 使用，必须与包名 + 签名 SHA-1 绑定；
 * - [KEY_WEB]：restapi.amap.com 的 HTTP 接口使用，不绑定包名。
 *
 * 存储文件：Key 单独放在 [PREFS_NAME]，该文件在备份规则里被排除，Key 不会随备份迁移到别的设备；
 * 收藏、城市、搜索历史等普通设置仍在 [LEGACY_PREFS_NAME] 里，照常参与备份。
 * 早期版本把 Key 存在 [LEGACY_PREFS_NAME]，首次访问时一次性搬到 [PREFS_NAME]（见 [migrateFromLegacy]）。
 *
 * 关于旧版本单输入框留下的值（均存放在 [KEY_WEB]）：
 * - f20cce0 时期输入框标注为「Web 服务 Key」，只用于 restapi；
 * - 04dee2a ~ 63669e1 时期输入框改标「Android Key」，同一个值同时喂给 SDK 与 restapi；
 * - c73dd94 起 [KEY_WEB] 成为 Web 服务 Key 的正式槽位。
 * 仅凭存储内容无法区分这个值到底是哪一类 Key（这些版本均未打发布 tag），
 * 因此不做槽位互换：值保留在 Web 槽位，Android 槽位为空时回落 BuildConfig 内置 Key，
 * 不再拿 Web 槽位的值去喂 SDK（否则只填了 Web Key 的用户会"SDK 必败 + Web 重试"）。
 *
 * 第 4 步从 object 改成类，由 Koin 提供单例。状态都在 [PREFS_NAME] 文件里，实现仍是伴生对象里的原函数，
 * 实例只绑定应用 Context；旧的 [AmapKeyStore] 暂时转发到伴生对象（第 12 步删）。
 */
class AmapKeyStorage(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    /** 高德 Key 专用文件（首次访问时从旧文件搬迁）；打开失败时为 null */
    fun prefs(): SharedPreferences? = prefs(appContext)

    fun readAndroidKey(): String? = readAndroidKey(appContext)

    fun readWebKey(): String? = readWebKey(appContext)

    fun writeAndroidKey(key: String?) = writeAndroidKey(appContext, key)

    fun writeWebKey(key: String?) = writeWebKey(appContext, key)

    companion object {
        /** 专用于高德 Key 的 SharedPreferences 文件（备份时排除） */
        const val PREFS_NAME = "trackr_amap_keys"

        /** 早期存放 Key 的通用设置文件 */
        const val LEGACY_PREFS_NAME = "trackr_prefs"

        const val KEY_ANDROID = "custom_amap_android_key"

        /** Web 服务 Key 槽位（历史上也是单输入框时代唯一的槽位） */
        const val KEY_WEB = "custom_amap_web_key"

        /** 标记已从 [LEGACY_PREFS_NAME] 搬迁完成 */
        internal const val KEY_MIGRATED_FROM_LEGACY = "migrated_from_trackr_prefs"

        fun prefs(context: Context): SharedPreferences? = runCatching {
            val keyPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!keyPrefs.getBoolean(KEY_MIGRATED_FROM_LEGACY, false)) {
                migrateFromLegacy(
                    legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE),
                    target = keyPrefs
                )
            }
            keyPrefs
        }.getOrNull()

        /**
         * 把旧文件里的两个 Key 搬到专用文件，并从旧文件删除。只执行一次。
         * 目标文件已有值时以目标为准（说明用户在新版本里重新填过），不覆盖。
         */
        internal fun migrateFromLegacy(legacy: SharedPreferences, target: SharedPreferences) {
            val editor = target.edit()
            listOf(KEY_ANDROID, KEY_WEB).forEach { key ->
                val legacyValue = legacy.getString(key, null)?.trim()?.ifBlank { null }
                if (legacyValue != null && target.getString(key, null).isNullOrBlank()) {
                    editor.putString(key, legacyValue)
                }
            }
            editor.putBoolean(KEY_MIGRATED_FROM_LEGACY, true)
            // 先确保新文件落盘，再删旧值，避免中途被杀导致 Key 丢失
            if (editor.commit()) {
                legacy.edit().remove(KEY_ANDROID).remove(KEY_WEB).apply()
            }
        }

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
}

/** 旧入口：第 4 步起实现在 [AmapKeyStorage]，这里只转发，第 12 步删 */
object AmapKeyStore {
    const val PREFS_NAME = AmapKeyStorage.PREFS_NAME
    const val LEGACY_PREFS_NAME = AmapKeyStorage.LEGACY_PREFS_NAME
    const val KEY_ANDROID = AmapKeyStorage.KEY_ANDROID
    const val KEY_WEB = AmapKeyStorage.KEY_WEB
    internal const val KEY_MIGRATED_FROM_LEGACY = AmapKeyStorage.KEY_MIGRATED_FROM_LEGACY

    fun prefs(context: Context): SharedPreferences? = AmapKeyStorage.prefs(context)

    internal fun migrateFromLegacy(legacy: SharedPreferences, target: SharedPreferences) =
        AmapKeyStorage.migrateFromLegacy(legacy, target)

    fun readAndroidKey(context: Context): String? = AmapKeyStorage.readAndroidKey(context)

    fun readAndroidKey(prefs: SharedPreferences?): String? = AmapKeyStorage.readAndroidKey(prefs)

    fun readWebKey(context: Context): String? = AmapKeyStorage.readWebKey(context)

    fun readWebKey(prefs: SharedPreferences?): String? = AmapKeyStorage.readWebKey(prefs)

    fun writeAndroidKey(context: Context, key: String?) = AmapKeyStorage.writeAndroidKey(context, key)

    fun writeAndroidKey(prefs: SharedPreferences?, key: String?) = AmapKeyStorage.writeAndroidKey(prefs, key)

    fun writeWebKey(context: Context, key: String?) = AmapKeyStorage.writeWebKey(context, key)

    fun writeWebKey(prefs: SharedPreferences?, key: String?) = AmapKeyStorage.writeWebKey(prefs, key)
}

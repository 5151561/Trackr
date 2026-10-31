package com.trackr.data.remote.amap

import android.content.Context
import android.os.Build
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.trackr.BuildConfig

/**
 * 高德 3D 地图、定位与搜索一体化 SDK 管理器。
 *
 * 高德开放平台的两类 Key **互不通用**，必须分开注入：
 * - **Android 平台 Key**：地图渲染（TextureMapView）、搜索 SDK、定位 SDK 使用，绑定「包名 + 签名 SHA-1」；
 * - **Web 服务 Key**：restapi.amap.com 的 HTTP 接口（公交算路、输入提示）使用，不绑定包名。
 * 把 Web 服务 Key 填进地图 SDK（或反过来）会直接导致底图鉴权失败、地图一片空白。
 *
 * 同时遵循零密钥泄漏规范：源码中不硬编码任何 Key，仅从
 * `local.properties` / 环境变量 / 用户运行期配置注入。
 */
object AmapSdkManager {
    private const val TAG = "AmapSdkManager"

    /** 3dmap SDK 随包带出的 ABI，缺失该 ABI 时地图原生库无法加载 */
    private val SUPPORTED_ABIS = setOf("arm64-v8a", "armeabi-v7a")

    @Volatile
    private var isPrivacyInitialized = false

    /** 已成功注入 SDK 的 Key，用于判断运行期改 Key 后是否需要重新注入 */
    @Volatile
    private var appliedKey: String? = null

    @Volatile
    private var lastInitError: String? = null

    /**
     * 更新高德地图、定位与搜索 SDK 隐私合规状态（符合工信部合规要求）。
     * 必须在任何 SDK 实例创建之前调用，否则 SDK 会拒绝初始化。
     */
    fun ensurePrivacyCompliance(context: Context) {
        if (!isPrivacyInitialized) {
            synchronized(this) {
                if (!isPrivacyInitialized) {
                    try {
                        MapsInitializer.updatePrivacyShow(context.applicationContext, true, true)
                        MapsInitializer.updatePrivacyAgree(context.applicationContext, true)
                        ServiceSettings.updatePrivacyShow(context.applicationContext, true, true)
                        ServiceSettings.updatePrivacyAgree(context.applicationContext, true)
                        AMapLocationClient.updatePrivacyShow(context.applicationContext, true, true)
                        AMapLocationClient.updatePrivacyAgree(context.applicationContext, true)
                        isPrivacyInitialized = true
                        Log.d(TAG, "AMap privacy compliance initialized")
                    } catch (e: Throwable) {
                        lastInitError = "高德 SDK 隐私合规初始化失败: ${e.javaClass.simpleName}"
                        Log.e(TAG, "Failed to initialize AMap privacy compliance", e)
                    }
                }
            }
        }
    }

    /**
     * 应用启动时尽早完成「隐私合规 + Key 注入」，
     * 保证界面第一次创建 TextureMapView 时 Key 已生效。
     */
    fun initialize(context: Context, androidKey: String?) {
        ensurePrivacyCompliance(context)
        applyApiKey(context, androidKey)
    }

    /**
     * 获取当前生效的高德 **Android 平台** Key。
     * 优先级：运行期用户配置 > BuildConfig.AMAP_ANDROID_KEY > 空
     */
    fun getEffectiveApiKey(customKey: String?): String {
        val userKey = customKey?.trim().orEmpty()
        if (userKey.isNotEmpty()) {
            return userKey
        }
        return BuildConfig.AMAP_ANDROID_KEY.trim()
    }

    /**
     * 获取当前生效的高德 **Web 服务** Key。
     * 优先级：运行期用户配置 > BuildConfig.AMAP_WEB_KEY > 空
     */
    fun getEffectiveWebServiceKey(customKey: String?): String {
        val userKey = customKey?.trim().orEmpty()
        if (userKey.isNotEmpty()) {
            return userKey
        }
        return BuildConfig.AMAP_WEB_KEY.trim()
    }

    fun isAndroidKeyConfigured(customKey: String?): Boolean = getEffectiveApiKey(customKey).isNotEmpty()

    fun isWebServiceKeyConfigured(customKey: String?): Boolean =
        getEffectiveWebServiceKey(customKey).isNotEmpty()

    /**
     * 当前设备 ABI 是否被 3dmap SDK 支持。
     * x86/x86_64 模拟器上无法加载 libAMapSDK_MAP，表现同样是地图永远空白。
     */
    fun isRuntimeAbiSupported(): Boolean =
        Build.SUPPORTED_ABIS.any { abi -> SUPPORTED_ABIS.contains(abi) }

    fun getLastInitError(): String? = lastInitError

    /**
     * 动态设置高德 SDK 的 API Key（仅 Android 平台 Key 有效）。
     * @param force 为 true 时即使 Key 未变化也重新注入（重建地图实例 / 用户点重试时使用）
     * @return 是否成功注入有效 Key
     */
    fun applyApiKey(context: Context, customKey: String?, force: Boolean = false): Boolean {
        ensurePrivacyCompliance(context)
        val effectiveKey = getEffectiveApiKey(customKey)
        if (effectiveKey.isEmpty()) {
            // 空 Key 时不再调用 SDK，避免刷屏日志；地图层会给出明确提示
            Log.w(TAG, "No valid AMap Android key available")
            return false
        }
        if (!force && effectiveKey == appliedKey) return true
        return try {
            MapsInitializer.setApiKey(effectiveKey)
            ServiceSettings.getInstance().setApiKey(effectiveKey)
            AMapLocationClient.setApiKey(effectiveKey)
            appliedKey = effectiveKey
            lastInitError = null
            Log.d(TAG, "AMap apiKey applied to map/search/location SDK")
            true
        } catch (e: Throwable) {
            lastInitError = "高德 SDK Key 注入失败: ${e.javaClass.simpleName}"
            Log.e(TAG, "Failed to set AMap apiKey", e)
            false
        }
    }

    /**
     * 缺少 Key 时给用户的可执行提示，避免只看到一片空地图却不知道原因。
     */
    fun missingKeyHint(): String =
        "未配置高德 Android Key，底图无法加载。请到高德开放平台创建「Android 平台」Key，" +
            "包名与 SHA-1 填当前运行环境的值（弹窗内可一键复制）。"

    /**
     * 地图不可用的原因（null 表示当前环境应能正常出图）。
     */
    fun mapUnavailableReason(customKey: String?): String? = when {
        !isRuntimeAbiSupported() ->
            "当前设备 ABI 为 ${Build.SUPPORTED_ABIS.joinToString("/")}，高德 3D 地图仅支持 arm64-v8a / armeabi-v7a，" +
                "请改用真机或 arm 架构模拟器。"
        !isAndroidKeyConfigured(customKey) -> missingKeyHint()
        lastInitError != null -> lastInitError
        else -> null
    }
}

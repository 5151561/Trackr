package com.trackr.core.domain.platform

/*
 * 第 17 步从 `:androidApp` 下沉的几个平台能力接口：功能模块（`:feature:*`）看不到 `:androidApp`，
 * 要用的平台能力都经这里的接口注入，实现留在 `:androidApp` 或对应 feature 的 androidMain。
 */

/** 把高德 Android Key 注入 SDK（Android：`AmapSdkManager.applyApiKey`；iOS 第 38 步） */
fun interface AmapSdkKeyApplier {
    fun apply(customKey: String?)
}

/** 高德 SDK 初始化器。配置构建时 Key 不会触碰 SDK；用户同意隐私说明后才调用 [initialize]. */
interface MapSdkInitializer {
    fun configureBuiltInKeys(builtInAndroidKey: String, builtInWebKey: String)
    fun initialize(userAndroidKey: String?)
}

/** 当前安装包的版本号（关于页「检查更新」比较用） */
data class AppVersionInfo(val code: Int, val name: String)

package com.trackr.data.remote.amap

import android.content.Context
import com.trackr.core.domain.platform.AmapSdkKeyApplier
import com.trackr.core.domain.platform.MapSdkInitializer

/**
 * [AmapSdkKeyApplier] 的 Android 实现，原样转调 [AmapSdkManager.applyApiKey]。
 * 接口第 17 步挪到 `:core:domain`（`:androidApp` 的 `AmapSdkKeySync` 也用它）。
 */
class AndroidAmapSdkKeyApplier(context: Context) : AmapSdkKeyApplier {

    private val appContext: Context = context.applicationContext ?: context

    override fun apply(customKey: String?) {
        AmapSdkManager.applyApiKey(appContext, customKey)
    }
}

/** [MapSdkInitializer] 的 Android 实现：记下构建时 Key，再做隐私合规 + 注入 Key（原 `TrackrApp` 里直接调） */
class AndroidMapSdkInitializer(context: Context) : MapSdkInitializer {

    private val appContext: Context = context.applicationContext ?: context

    override fun configureBuiltInKeys(builtInAndroidKey: String, builtInWebKey: String) {
        AmapSdkManager.setBuiltInKeys(androidKey = builtInAndroidKey, webKey = builtInWebKey)
    }

    override fun initialize(userAndroidKey: String?) {
        AmapSdkManager.initialize(appContext, userAndroidKey)
    }
}

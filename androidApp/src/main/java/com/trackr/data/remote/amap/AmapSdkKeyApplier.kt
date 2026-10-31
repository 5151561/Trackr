package com.trackr.data.remote.amap

import android.content.Context

/**
 * 把高德 Android Key 注入 SDK。ViewModel 通过它注入，不再自己拿 Application。
 * Android 实现原样转调 [AmapSdkManager.applyApiKey]。
 */
fun interface AmapSdkKeyApplier {
    fun apply(customKey: String?)
}

class AndroidAmapSdkKeyApplier(context: Context) : AmapSdkKeyApplier {

    private val appContext: Context = context.applicationContext ?: context

    override fun apply(customKey: String?) {
        AmapSdkManager.applyApiKey(appContext, customKey)
    }
}

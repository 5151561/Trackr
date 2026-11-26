package com.trackr.data.remote.amap

import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.domain.platform.AmapSdkKeyApplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 用户改了 Android 槽的 Key 就重新注入高德 SDK（第 11 步）。
 *
 * 原来由路线规划页的 ViewModel 在保存时注入；Key 对话框挪到「更多」页以后，谁改都经 [AmapKeyRepository]，
 * 所以在应用级收集一次。启动时的值已由 `TrackrApp` 里的 `AmapSdkManager.initialize` 注入，这里跳过当前值。
 */
class AmapSdkKeySync(
    private val amapKeys: AmapKeyRepository,
    private val applier: AmapSdkKeyApplier,
    private val appScope: CoroutineScope,
    /** 注入 SDK 的线程：和原来一样在主线程；测试可替换 */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {

    private var job: Job? = null

    /** 开始收集；重复调用不会开第二个收集 */
    fun start() {
        if (job?.isActive == true) return
        job = appScope.launch(dispatcher) {
            amapKeys.androidKey
                .drop(1)
                .collect { key ->
                    if (amapKeys.privacyConsent.value == true) {
                        runCatching { applier.apply(key) }
                    }
                }
        }
    }
}

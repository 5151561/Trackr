package com.trackr.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trackr.core.model.UiMode

/**
 * 按界面模式放标准版 / 长辈版的导航（§8.4，Q9：切换模式直接回首页）。
 *
 * 两种模式在 `when` 的不同分支里：切换时旧模式整棵树离开组合，它的返回栈（`rememberSaveable`）跟着丢掉，
 * 新模式从首页开始；再切回来，标准版 3 条页签栈也都是各自根页。
 *
 * 页面级 ViewModel（NavDisplay 每个 entry 一份）放在按模式分开的 [ViewModelStore] 里：整棵树离开组合时
 * entry 没有被「弹出」，Navigation 3 不会替它清 ViewModel，所以切走后由这里把旧模式的整份清掉，
 * 不留到下次同名页面复用。配置变更（转屏）时模式没变，什么都不清。
 * Activity 级共享的 ViewModel（首页、规划、壳）不在这里，仍挂在 Activity 上。
 */
@Composable
internal fun UiModeNavHost(
    uiMode: UiMode,
    standard: @Composable () -> Unit,
    elder: @Composable () -> Unit,
) {
    val parent = checkNotNull(LocalViewModelStoreOwner.current) { "UiModeNavHost 要放在有 ViewModelStoreOwner 的地方" }
    val stores = viewModel(viewModelStoreOwner = parent) { UiModeViewModelStores() }
    val owner = remember(stores, parent, uiMode) { modeViewModelStoreOwner(stores.storeFor(uiMode), parent) }
    // 旧模式的树已经在这一帧离开组合，之后再清它的 ViewModel
    LaunchedEffect(stores, uiMode) { stores.clearAllExcept(uiMode) }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        when (uiMode) {
            UiMode.Standard -> standard()
            UiMode.Elder -> elder()
        }
    }
}

/** 每种界面模式一份 [ViewModelStore]，挂在 Activity 上（转屏不丢），Activity 结束时一起清。 */
internal class UiModeViewModelStores : ViewModel() {
    private val stores = mutableMapOf<UiMode, ViewModelStore>()

    fun storeFor(mode: UiMode): ViewModelStore = stores.getOrPut(mode) { ViewModelStore() }

    /** 清掉 [mode] 以外的模式留下的 ViewModel */
    fun clearAllExcept(mode: UiMode) {
        stores.keys.filter { it != mode }.forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/** 用 [store] 存 ViewModel；默认工厂和 CreationExtras 沿用上一级（Activity），和原来直接挂在 Activity 上一样 */
private fun modeViewModelStoreOwner(store: ViewModelStore, parent: ViewModelStoreOwner): ViewModelStoreOwner =
    if (parent is HasDefaultViewModelProviderFactory) {
        object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory by parent {
            override val viewModelStore: ViewModelStore = store
        }
    } else {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

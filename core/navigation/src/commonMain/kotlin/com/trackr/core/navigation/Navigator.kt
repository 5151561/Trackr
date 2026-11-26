package com.trackr.core.navigation

import androidx.navigation3.runtime.NavKey
import com.trackr.core.navigation.deeplink.DeepLink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 所有导航动作都走这里（§3.3）。纯 Kotlin，只改 [NavigationState]，`NavigatorTest` 直接测（修 N7）。
 */
class Navigator(private val state: NavigationState) : TrackrNavigator {

    private val _reselect = MutableSharedFlow<ScreenKey>(extraBufferCapacity = 1)

    /** 再点当前页签时发出该页签（栈已回到根）；以后给列表回顶用。 */
    val reselect: SharedFlow<ScreenKey> = _reselect.asSharedFlow()

    /** 点底栏：切到别的页签保留各自的栈；再点当前页签则回到它的根页。 */
    fun selectTab(tab: ScreenKey) {
        require(tab in state.stacks) { "$tab 不是顶层页签" }
        if (tab == state.topLevel) {
            state.currentStack.popToRoot()
            _reselect.tryEmit(tab)
        } else {
            state.topLevel = tab
        }
    }

    /** 在当前页签的栈上打开 [key]；栈顶已经是它就不重复压栈（保留旧行为，防连点）。 */
    override fun open(key: ScreenKey) {
        state.currentStack.pushIfNotOnTop(key)
    }

    /**
     * 落一条 deep link（§3.6 合成返回栈）：切到 [DeepLink.Stack.tab]，它的栈重置为 `[页签根, keys…]`，
     * 按返回回到页签根页而不是退出 App。别的页签的栈不动。同一条链接重放结果不变。
     * 页签不存在（不该发生）就落到起始页签；keys 里的页签根和连续重复的 Key 跳过。
     */
    fun applyDeepLink(link: DeepLink.Stack) {
        val tab = link.tab.takeIf { it in state.stacks } ?: state.start
        val stack = state.stacks.getValue(tab)
        state.topLevel = tab
        stack.popToRoot()
        link.keys.forEach { key -> if (key != tab) stack.pushIfNotOnTop(key) }
    }

    /**
     * 切到 [tab]，并让 [key] 成为它的栈顶且在栈里只出现一次：栈里已有 [key] 就退到它，没有就先回根再压 [key]。
     * 规划页「去收藏」用（第 17 步「收藏」从页签变成首页的子页），不会叠出 [Home, LineDetail, FavoritesGroup] 或两个收藏页。
     */
    override fun showInTab(tab: ScreenKey, key: ScreenKey) {
        require(tab in state.stacks) { "$tab 不是顶层页签" }
        state.topLevel = tab
        val stack = state.stacks.getValue(tab)
        val index = stack.indexOf(key)
        if (index >= 0) {
            while (stack.size > index + 1) stack.removeLastOrNull()
        } else {
            stack.popToRoot()
            stack.pushIfNotOnTop(key)
        }
    }

    /**
     * 返回：当前栈有子页就弹一页；非起始页签在根页就回起始页签；起始页签根页返回 false，交给系统退出。
     */
    fun back(): Boolean {
        val stack = state.currentStack
        return when {
            stack.size > 1 -> {
                stack.removeLastOrNull()
                true
            }
            state.topLevel != state.start -> {
                state.topLevel = state.start
                true
            }
            else -> false
        }
    }

    /** 子页左上角的返回：只在当前栈里退一页，已在根页就什么都不做（不切页签，和系统返回不同）。 */
    override fun popChildPage() {
        val stack = state.currentStack
        if (stack.size > 1) stack.removeLastOrNull()
    }

    /**
     * 关掉当前栈里的弹层 [key]（弹层自己关：选完了、待选没了、会话换了）。
     * 不用 [back]：弹层关的时候用户可能已经按了返回，再退一页会把下面的页也退掉。不在栈里就什么都不做。
     */
    override fun dismiss(key: ScreenKey) {
        val stack = state.currentStack
        val index = stack.lastIndexOf(key)
        if (index > 0) stack.removeAt(index)
    }

    /** 页签集合变化（D2：没 Key 隐藏「路线」）：当前页签不在了就清空它的栈并回起始页签。 */
    fun onTabsChanged(visible: Set<ScreenKey>) {
        if (state.topLevel !in visible) {
            state.currentStack.popToRoot()
            state.topLevel = state.start
        }
    }

    private fun MutableList<NavKey>.pushIfNotOnTop(key: ScreenKey) {
        if (lastOrNull() != key) add(key)
    }

    private fun MutableList<NavKey>.popToRoot() {
        while (size > 1) removeLastOrNull()
    }
}

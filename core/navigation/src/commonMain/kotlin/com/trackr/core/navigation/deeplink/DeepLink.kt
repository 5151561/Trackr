package com.trackr.core.navigation.deeplink

import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.navigation.ScreenKey

/**
 * `trackr://` 链接解析出来的结果（重构计划 §3.6）。解析器只产出它，怎么落栈由当前模式的导航器决定（§8.4）。
 */
sealed interface DeepLink {

    /** 合成返回栈：切到 [tab]，它的栈重置为 `[tab, keys…]`（按返回回到页签根页，不退出 App）。 */
    data class Stack(val tab: ScreenKey, val keys: List<ScreenKey>) : DeepLink

    /** 「上了哪辆？」弹层（§2.6 第 ④ 条）：[ScreenKey.ChooseBoardedBus] 叠在当前栈顶，别的不动。 */
    data class ChooseBoardedBus(val sessionId: String) : DeepLink

    /** 不入栈，直接拉起乘车码（静态快捷方式、通知「乘车码」按钮）。 */
    data class TransitCode(val provider: TransitCodeProvider) : DeepLink
}

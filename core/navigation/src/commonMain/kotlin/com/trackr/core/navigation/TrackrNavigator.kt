package com.trackr.core.navigation

/**
 * 页面用的导航动作（§8.4、§8.5）。各 feature 的 entry 和 Route 只依赖它，标准版的 [Navigator]（3 条页签栈）
 * 和长辈版的 [ElderNavigator]（1 条栈）各自实现，同一个页面两种模式都能放。
 * 页签相关的动作（点底栏、页签集合变化）只有标准版有，不在这里。
 */
interface TrackrNavigator {

    /** 打开 [key]；栈顶已经是它就不重复压栈（防连点）。 */
    fun open(key: ScreenKey)

    /** 子页左上角的返回：退一页，已在根页就什么都不做。 */
    fun popChildPage()

    /** 弹层 [key] 自己关掉：从栈里拿掉它，不在栈里就什么都不做。 */
    fun dismiss(key: ScreenKey)

    /** 让 [key] 成为 [tab] 的栈顶且只出现一次（栈里有就退到它，没有就回根再压）。长辈版只有一条栈，忽略 [tab]。 */
    fun showInTab(tab: ScreenKey, key: ScreenKey)
}

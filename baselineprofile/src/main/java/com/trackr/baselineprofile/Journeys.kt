package com.trackr.baselineprofile

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/**
 * 关键旅程（重构计划 §4.6）：① 冷启动到首页；② 首页滚动；③ 打开线路详情；④ 切到「我的」。
 * Baseline Profile 生成器和 Macrobenchmark 共用。
 *
 * 元素按界面上的文字找（底栏「公交 / 路线 / 我的」、首启「同意并继续」），不加 testTag，不改应用代码。
 */

/** 被测应用包名；`baselineprofile` 插件会传 `targetAppId`，没传时用正式包名。 */
internal val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: "com.github.u5151561.trackr"

/**
 * 旅程 ③ 要打开的线路：`trackr://line/{cityId}/{lineId}?source=...`（DeepLinkUris.line 的格式）。
 * 线路 id 跟数据源和城市有关，代码里不写死，跑的时候用
 * `-Pandroid.testInstrumentationRunnerArguments.lineLink=trackr://line/...` 传进来；没传就跳过这一段。
 */
private val lineLink: String?
    get() = InstrumentationRegistry.getArguments().getString("lineLink")?.takeIf { it.isNotBlank() }

private const val UI_TIMEOUT_MS = 5_000L

private const val TAB_HOME = "公交"
private const val TAB_ME = "我的"
private const val PRIVACY_ACCEPT = "同意并继续"

/** ① 冷启动并等首页底栏出来；首次安装时先点掉隐私说明。 */
internal fun MacrobenchmarkScope.startAndWaitForHome() {
    pressHome()
    startActivityAndWait()
    acceptPrivacyIfShown()
    device.wait(Until.hasObject(By.text(TAB_HOME)), UI_TIMEOUT_MS)
}

/** 首次启动的隐私说明（TrackrRoot 里的「同意并继续」）。之后的启动不会再出现。 */
internal fun MacrobenchmarkScope.acceptPrivacyIfShown() {
    val accept = device.findObject(By.text(PRIVACY_ACCEPT)) ?: return
    accept.click()
    device.waitForIdle()
}

/** ② 首页列表来回甩两次；没收藏、列表不可滚时什么都不做。 */
internal fun MacrobenchmarkScope.scrollHome() {
    val list = device.findObject(By.scrollable(true)) ?: return
    // 留出边距，别从屏幕边缘起手触发返回手势
    list.setGestureMargin(device.displayWidth / 5)
    repeat(2) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}

/** ③ 用 deep link 打开线路详情，等走向条（「车辆」图标或「返回」按钮）出来，再退回首页。 */
internal fun MacrobenchmarkScope.openLineDetail() {
    val link = lineLink ?: return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        .setClassName(targetPackage, "com.trackr.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    startActivityAndWait(intent)
    device.wait(Until.hasObject(By.desc("车辆")), UI_TIMEOUT_MS)
    device.pressBack()
    device.wait(Until.hasObject(By.text(TAB_HOME)), UI_TIMEOUT_MS)
}

/** ④ 点底栏「我的」，等页面出来，再点回「公交」。 */
internal fun MacrobenchmarkScope.switchToMeAndBack() {
    device.findObject(By.text(TAB_ME))?.click() ?: return
    device.waitForIdle()
    device.findObject(By.text(TAB_HOME))?.click()
    device.waitForIdle()
}

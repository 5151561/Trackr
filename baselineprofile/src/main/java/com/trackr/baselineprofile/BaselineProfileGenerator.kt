package com.trackr.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成 Baseline Profile（要连真机，API 28+）：
 *
 *   ./gradlew :androidApp:generateBaselineProfile
 *
 * 结果由 baselineprofile 插件写进 `androidApp/src/release/generated/baselineProfiles/`，提交进仓库；
 * 之后的 release 包会带上它，profileinstaller 在安装后把它交给 ART 预编译。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = targetPackage,
        // 启动路径同时写进 startup profile，供 dex 布局优化用
        includeInStartupProfile = true,
    ) {
        startAndWaitForHome()
        scrollHome()
        openLineDetail()
        switchToMeAndBack()
    }
}

package com.trackr.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 首页滚动 + 切页基准（记录 frameDurationCpuMs P90），对比不预编译和带 Baseline Profile 两种情况：
 *
 *   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.trackr.baselineprofile.ScrollBenchmark
 *
 * 首页要有几条收藏才有东西可滚；没收藏时只量切到「我的」再切回来。
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollCompilationNone() = scroll(CompilationMode.None())

    @Test
    fun scrollBaselineProfile() =
        scroll(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun scroll(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        // 启动不计入帧耗时：在 setupBlock 里启动到首页
        setupBlock = { startAndWaitForHome() },
    ) {
        scrollHome()
        switchToMeAndBack()
    }

    private companion object {
        const val ITERATIONS = 10
    }
}

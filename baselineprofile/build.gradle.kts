// 第 27 步：Baseline Profile 生成器 + Macrobenchmark（重构计划 §4.6）。
// 都要连真机跑，单元测试（./gradlew test）不包含它们：
//   生成 Baseline Profile：./gradlew :androidApp:generateBaselineProfile
//   跑基准：./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
plugins {
    alias(libs.plugins.trackr.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.trackr.baselineprofile"

    defaultConfig {
        // BaselineProfileRule 要 API 28+（Android 9）
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":androidApp"
}

// 用连着的真机生成和测量（不建 Gradle 托管模拟器）
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

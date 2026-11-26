plugins {
    alias(libs.plugins.android.application) apply false
    // 第 27 步：:baselineprofile（com.android.test + androidx.baselineprofile）
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    // 约定插件 trackr.kmp.* 要用；这里只放进构建类路径（:core:designsystem 起应用）
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    // 第 9 步：:core:database 用 Room KMP（KSP 生成代码）
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}


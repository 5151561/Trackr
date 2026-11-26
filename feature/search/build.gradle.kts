// :feature:search：搜索（重构计划 §2.2）。第 23 步起页面、ViewModel、Koin 模块、entry 都在 commonMain。
// 共用依赖由约定插件 trackr.kmp.feature 提供。
plugins {
    alias(libs.plugins.trackr.kmp.feature)
}

kotlin {
    android {
        // Compose 资源（页面文案）在 Android 上走 Android 资源 / assets 打进 APK。
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            // 输入框文字存 SavedStateHandle（§3.4、§3.8）
            implementation(libs.jetbrains.lifecycle.viewmodel.savedstate)
            // 图标（ic_back / ic_search / ic_close / ic_chevron_right）
            implementation(libs.compose.mp.components.resources)
        }
    }
}

compose.resources {
    packageOfResClass = "com.trackr.feature.search.resources"
}

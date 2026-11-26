// :feature:line：线路详情 + 时刻表 + 定时提醒入口（重构计划 §2.2）。ViewModel、Koin 模块、entry、页面都在 commonMain
// （第 20 步 v3 重写）；共用依赖由约定插件 trackr.kmp.feature 提供。
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
            // :core:designsystem 的 Compose 资源图标（vectorResource(Res.drawable.ic_xxx)）
            implementation(libs.compose.mp.components.resources)
        }
    }
}

compose.resources {
    packageOfResClass = "com.trackr.feature.line.resources"
}

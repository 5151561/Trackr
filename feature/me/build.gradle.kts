// :feature:me：我的（设置、定时提醒编辑、高德 Key、关于）（重构计划 §2.2）。第 25 步页面全部在 commonMain。
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
            // 设计系统的图标（com.trackr.core.designsystem.resources.Res）要用 vectorResource
            implementation(libs.compose.mp.components.resources)
        }
    }
}

compose.resources {
    packageOfResClass = "com.trackr.feature.me.resources"
}

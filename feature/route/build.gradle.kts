// :feature:route：路线规划（地图）+ 路线详情（重构计划 §2.2）。ViewModel、Koin 模块、entry 声明在 commonMain；
// 规划页（第 24 步）在 commonMain，地图（expect RouteMap）的高德 Android SDK 实现（§2.9）放 androidMain。共用依赖由约定插件 trackr.kmp.feature 提供。
plugins {
    alias(libs.plugins.trackr.kmp.feature)
}

kotlin {
    android {
        // 主机测试里也要能读到 :core:designsystem 的 Compose 资源（图标），否则 vectorResource 一直等不到
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:network"))
            // 第 24 步：规划页进 commonMain，图标用 :core:designsystem 的 Compose 资源
            implementation(libs.compose.mp.components.resources)
        }
        androidMain.dependencies {
            // 高德 3D 地图 + 定位 + 搜索一体化 SDK
            implementation(libs.amap.search)
        }
    }
}

compose.resources {
    packageOfResClass = "com.trackr.feature.route.resources"
}

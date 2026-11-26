// 导航：KMP + Compose Multiplatform（Android + iosArm64 + iosSimulatorArm64），CMP 版 Navigation 3。
// ScreenKey、NavigationState（每个页签一条返回栈）、Navigator（纯逻辑，commonTest 直接测）、
// TrackrTransitions、TopLevel，以及注册 ScreenKey 多态序列化的 SavedStateConfiguration（重构计划 §3.2、§3.3、§3.8）；
// 第 18 步加 DeepLinkParser / DeepLinkUris（§3.6）、BottomSheetSceneStrategy（§3.7），entry 共用一个 ResultEventBus（§3.5）。
// Android 上 org.jetbrains.androidx.* 解析成同版本的 androidx 库（navigation3-ui 1.2.0、lifecycle-viewmodel-navigation3 2.11.0）。
plugins {
    alias(libs.plugins.trackr.kmp.library)
    alias(libs.plugins.trackr.kmp.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.runtime.saveable)
            implementation(libs.compose.mp.animation)
            // 第 18 步：BottomSheetSceneStrategy 用 ModalBottomSheet
            implementation(libs.compose.mp.material3)
            api(libs.androidx.navigation3.runtime)
            api(libs.jetbrains.navigation3.ui)
            api(libs.jetbrains.lifecycle.viewmodel.navigation3)
            api(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            runtimeOnly("org.robolectric:android-all:14-robolectric-10818077")
        }
    }
}

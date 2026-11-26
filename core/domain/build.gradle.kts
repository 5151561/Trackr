// 领域层：KMP 纯 Kotlin（Android + iosArm64 + iosSimulatorArm64）。
// 到站文字与口径、追踪的纯函数状态机、坐标投影与转换，以及平台能力接口（实现放 :androidApp / iosMain）。
// commonMain 不碰 java.* / android.*（重构计划 §2.1）。
plugins {
    alias(libs.plugins.trackr.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":core:testing"))
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

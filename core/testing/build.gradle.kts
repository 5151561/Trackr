// 测试辅助：KMP（Android + iosArm64 + iosSimulatorArm64），给各模块的测试用。
// 平台能力接口的 Fake 和 FakeClock（第 7 步）；:core:data 仓库接口的 Fake（第 8 步，由原 FakeTransitRepository 按接口拆出）。
plugins {
    alias(libs.plugins.trackr.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:domain"))
            api(project(":core:data"))
            api(libs.kotlinx.coroutines.core)
            // 第 17 步：功能模块 commonTest 用的主调度器替换（MainDispatcherHolder）
            api(libs.kotlinx.coroutines.test)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

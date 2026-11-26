// 网络层：KMP（Android + iosArm64 + iosSimulatorArm64）。
// Ktor 客户端（Android 用 OkHttp 引擎、iOS 用 Darwin 引擎）、高德 Web API、Trackr 数据源协议客户端（CustomTransitProvider，
// 不内置任何第三方数据源），以及请求合并缓存 SingleFlightCache 和 LRU。重构计划 §2.2、§2.10。
plugins {
    alias(libs.plugins.trackr.kmp.library)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:domain"))
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.atomicfu)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
            // 兼容层和真正的 org.json 逐个字段对比（JsonCompatParityTest）
            implementation(libs.org.json)
        }
    }
}

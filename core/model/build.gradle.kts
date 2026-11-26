// 领域模型：KMP 纯 Kotlin（Android + iosArm64 + iosSimulatorArm64），只放 @Serializable 数据类、枚举和城市目录。
// 不依赖 Compose；页面直接拿来当参数的扁平模型在根目录 compose_stability.conf 里登记为稳定。
plugins {
    alias(libs.plugins.trackr.kmp.library)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
        }
    }
}

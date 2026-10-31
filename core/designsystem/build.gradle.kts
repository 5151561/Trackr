// 约定插件 trackr.android.library（build-logic/convention）统一配置：
// compileSdk 37 / minSdk 26、JDK 21 工具链、Java 17 字节码、单元测试（含 Android 资源、2 GB 堆）、Compose 编译器。
// 第 5 步改成 trackr.kmp.library + trackr.kmp.compose。
plugins {
    alias(libs.plugins.trackr.android.library)
    alias(libs.plugins.kotlin.plugin.compose)
}

android {
    namespace = "com.trackr.core.designsystem"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.graphics.shapes)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testRuntimeOnly("org.robolectric:android-all:14-robolectric-10818077")

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

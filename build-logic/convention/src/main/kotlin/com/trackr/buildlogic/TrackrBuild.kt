package com.trackr.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** 全工程统一的 SDK / JDK 版本。 */
internal object TrackrVersions {
    const val COMPILE_SDK = 37
    const val MIN_SDK = 26
    const val TARGET_SDK = 36

    /** Gradle 编译用的 JDK 工具链。 */
    const val JDK_TOOLCHAIN = 21

    /** 产物的 Java 字节码版本。 */
    val JAVA_TARGET = JavaVersion.VERSION_17

    /** 单元测试（含 Robolectric）的堆上限。 */
    const val TEST_MAX_HEAP = "2048m"
}

internal fun Project.configureJavaToolchain() {
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(TrackrVersions.JDK_TOOLCHAIN))
    }
}

/** `com.android.application` 与 `com.android.library` 共用的配置。 */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.compileSdk = TrackrVersions.COMPILE_SDK
    android.defaultConfig.minSdk = TrackrVersions.MIN_SDK
    android.compileOptions.sourceCompatibility = TrackrVersions.JAVA_TARGET
    android.compileOptions.targetCompatibility = TrackrVersions.JAVA_TARGET
    android.testOptions.unitTests.isIncludeAndroidResources = true
    android.testOptions.unitTests.all { it.maxHeapSize = TrackrVersions.TEST_MAX_HEAP }
    configureJavaToolchain()
}

/**
 * Compose 编译器：稳定性配置 + 按需输出 reports/metrics（重构计划 §4.1）。
 * 生成报告：./gradlew :androidApp:compileReleaseKotlin -PcomposeReports --rerun
 * （报告目录不算编译任务的输入，编译已是最新时不加 --rerun 不会生成）
 */
internal fun Project.configureComposeCompiler() {
    extensions.configure<ComposeCompilerGradlePluginExtension> {
        stabilityConfigurationFiles.add(
            rootProject.layout.projectDirectory.file("compose_stability.conf"),
        )
        if (providers.gradleProperty("composeReports").isPresent) {
            reportsDestination.set(layout.buildDirectory.dir("compose_reports"))
            metricsDestination.set(layout.buildDirectory.dir("compose_metrics"))
        }
    }
}

/** `:core:model` -> `com.trackr.core.model`；模块里显式写了 namespace 就以模块为准。 */
internal fun Project.defaultNamespace(): String =
    "com.trackr" + path.replace(':', '.')

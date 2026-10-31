import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.trackr.buildlogic.TrackrVersions
import com.trackr.buildlogic.defaultNamespace
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * trackr.kmp.library：KMP 库（`:core:*`）。
 * 目标平台：Android（AGP 的 Android KMP 库插件，开 Android 主机测试）+ iosArm64 + iosSimulatorArm64。
 * namespace 默认按模块路径生成（`:core:model` -> `com.trackr.core.model`），模块可覆盖。
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")

            extensions.configure<KotlinMultiplatformExtension> {
                jvmToolchain(TrackrVersions.JDK_TOOLCHAIN)

                (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                    namespace = defaultNamespace()
                    compileSdk = TrackrVersions.COMPILE_SDK
                    minSdk = TrackrVersions.MIN_SDK
                    compilerOptions {
                        jvmTarget.set(JvmTarget.fromTarget(TrackrVersions.JAVA_TARGET.toString()))
                    }
                    withHostTest {
                        isIncludeAndroidResources = true
                    }
                }

                iosArm64()
                iosSimulatorArm64()
            }

            tasks.withType<Test>().configureEach {
                maxHeapSize = TrackrVersions.TEST_MAX_HEAP
            }

            // KMP 模块没有叫 `test` 的任务，根目录 `./gradlew test` 会把它漏掉。
            // 补一个只跑 Android 主机测试的 `test`，让 `./gradlew test` 仍是「全部 JVM 单元测试」；
            // iOS 测试另跑 `iosSimulatorArm64Test`（需要 Xcode）。
            tasks.register("test") {
                group = "verification"
                description = "Runs the Android host (JVM) unit tests of this KMP module."
                dependsOn("testAndroidHostTest")
            }
        }
    }
}

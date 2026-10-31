import com.android.build.api.dsl.ApplicationExtension
import com.trackr.buildlogic.TrackrVersions
import com.trackr.buildlogic.configureAndroidCommon
import com.trackr.buildlogic.configureComposeCompiler
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * trackr.android.application：`:androidApp`（Android 入口）。
 * 统一 SDK / JDK / 单元测试配置；模块再应用 Compose 编译器插件时，顺带开 Compose 并配好稳定性与报告。
 * applicationId、版本号、签名、混淆、构建类型留在模块自己的 build.gradle.kts。
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = TrackrVersions.TARGET_SDK
            }

            pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
                extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
                configureComposeCompiler()
            }
        }
    }
}

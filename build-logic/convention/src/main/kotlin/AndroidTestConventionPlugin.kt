import com.android.build.api.dsl.TestExtension
import com.trackr.buildlogic.TrackrVersions
import com.trackr.buildlogic.configureAndroidCommon
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * trackr.android.test：独立的仪器测试模块（第 27 步的 `:baselineprofile`）。
 * 和 `:androidApp` 用同一套 SDK / JDK 配置；被测应用（targetProjectPath）由模块自己写。
 */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.test")

            extensions.configure<TestExtension> {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = TrackrVersions.TARGET_SDK
            }
        }
    }
}

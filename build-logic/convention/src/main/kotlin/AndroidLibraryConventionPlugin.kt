import com.android.build.api.dsl.LibraryExtension
import com.trackr.buildlogic.configureAndroidCommon
import com.trackr.buildlogic.configureComposeCompiler
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * trackr.android.library：只给还没改成 KMP 的纯 Android 库用（现在只有 `:core:designsystem`）。
 * 第 5 步 `:core:designsystem` 改成 `trackr.kmp.library` + `trackr.kmp.compose` 后，这个插件就没人用了，可以删。
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
            }

            pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
                extensions.configure<LibraryExtension> { buildFeatures.compose = true }
                configureComposeCompiler()
            }
        }
    }
}

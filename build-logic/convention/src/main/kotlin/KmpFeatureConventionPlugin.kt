import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * trackr.kmp.feature：功能模块（`:feature:*`）= `trackr.kmp.library` + `trackr.kmp.compose`。
 * 各 feature 共用的 `:core:*` 依赖等这些模块建出来再加（第 17 步）。
 */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("trackr.kmp.library")
            pluginManager.apply("trackr.kmp.compose")
        }
    }
}

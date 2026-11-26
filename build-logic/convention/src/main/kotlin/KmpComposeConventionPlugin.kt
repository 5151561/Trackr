import com.trackr.buildlogic.configureComposeCompiler
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * trackr.kmp.compose：给 KMP 模块加 Compose Multiplatform（和 `trackr.kmp.library` 一起用）。
 * 应用 CMP 插件与 Compose 编译器插件，配好稳定性配置与 reports；Compose 依赖由模块自己声明。
 */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            configureComposeCompiler()
        }
    }
}

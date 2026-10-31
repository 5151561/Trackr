// 约定插件的独立构建（重构计划 §2.2、第 2 步）。根工程的 settings.gradle.kts 用 includeBuild 引入。
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")

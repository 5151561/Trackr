plugins {
    `kotlin-dsl`
}

group = "com.trackr.buildlogic"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// compileOnly：插件本体由根工程 build.gradle.kts 的 `apply false` 放进构建类路径，
// 这里只拿来编译，避免同一个插件出现两个版本。
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "trackr.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("kmpLibrary") {
            id = "trackr.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpCompose") {
            id = "trackr.kmp.compose"
            implementationClass = "KmpComposeConventionPlugin"
        }
        register("kmpFeature") {
            id = "trackr.kmp.feature"
            implementationClass = "KmpFeatureConventionPlugin"
        }
    }
}

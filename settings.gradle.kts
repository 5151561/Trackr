pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 仅对高德 SDK 生效：其 jar 体积大（30MB+），Maven Central 在国内拉取极慢，
        // 用阿里云镜像加速；其余依赖仍走官方仓库，未命中时自动回落。
        maven("https://maven.aliyun.com/repository/public") {
            content { includeGroup("com.amap.api") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Trackr"
include(":app")
include(":core:designsystem")

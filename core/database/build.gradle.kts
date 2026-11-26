// 本地数据库：Room KMP（Android + iosArm64 + iosSimulatorArm64），重构计划 §2.4 / 第 9 步。
// 表：收藏的车、路线（路线 → 程 → 候选线）、收藏分组、定时提醒、搜索历史、线路站表缓存、迁移标记。
// Android 用系统自带 SQLite（不打包 .so，APK 不变大，Robolectric 也能跑）；iOS 用 BundledSQLiteDriver。
// schema 导出到 schemas/，以后改表结构时写 Migration 并用它做迁移测试。
plugins {
    alias(libs.plugins.trackr.kmp.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.androidx.room.runtime)
            api(libs.kotlinx.coroutines.core)
        }
        iosMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.robolectric)
            runtimeOnly("org.robolectric:android-all:14-robolectric-10818077")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}

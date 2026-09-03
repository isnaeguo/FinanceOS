import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.androidx.room3)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    android {
        namespace = "com.financeos.shared"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        withHostTest {}
    }

    // iOS 准备：启用 Apple targets 并为每个 target 导出静态 Framework，
    // 供未来的 Xcode iOS App 直接链接使用。
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "FinanceOSShared"
            isStatic = true
        }
    }

    // macOS 与 iOS 复用同一业务内核，同样导出静态 Framework 供 apple-xcode 主工程链接。
    macosArm64 {
        binaries.framework {
            baseName = "FinanceOSShared"
            isStatic = true
        }
    }


    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
    add("kspJvm", libs.androidx.room3.compiler)
    // Native target 同样需要 Room 编译器生成 FinanceOsDatabaseConstructor 的 actual，否则 iOS 编译失败。
    add("kspIosArm64", libs.androidx.room3.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room3.compiler)
    add("kspMacosArm64", libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// 当前 AGP/KSP 组合未自动声明 Host Test Lint 对生成源码的依赖，显式排序可避免 Gradle 9 拒绝完整 build。
tasks.matching {
    it.name == "generateAndroidHostTestLintModel" ||
        it.name == "lintAnalyzeAndroidHostTest"
}.configureEach {
    dependsOn("kspAndroidHostTest")
}

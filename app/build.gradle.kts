import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.yg.tgdrive"
    compileSdk = 35

    // ---- 版本纪律（发布必读） ----
    // 每次出包 versionCode 必须 +1（不论 debug/release、不论改动大小），否则已装机器
    // 无法升级覆盖，且崩溃日志里的版本号无法区分。versionName 同步递增：
    // 新功能/行为变化 +0.1.0；仅修 bug +0.0.1。两者都在本文件硬编码（随 git 可追溯），
    // 不放进仓库外的 properties——版本号必须和源码一起被 review。
    // 当前：0.3.0 / 3（Track B：release 签名 + 本地崩溃日志 + 版本纪律）
    defaultConfig {
        applicationId = "com.yg.tgdrive"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // ---- release 签名 ----
    // 密钥材料在仓库外（/workspace/tgdrive-secrets），绝不入库。
    // 文件缺失时保持 signingConfig 为 null：release 照常编译，产出 unsigned APK，
    // 不因缺密钥而让 CI/新机器构建失败。
    val keystorePropsFile = File("/workspace/tgdrive-secrets/keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.isFile) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }
    val hasKeystore = keystoreProps.getProperty("storeFile")?.let { File(it).isFile } == true

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 现代 APK 签名（v1 关闭、v2/v3 开启），minSdk 24 无需 v1
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // release 绝不内置凭据：false 让 R8 把凭据分支折叠成 null
            buildConfigField("boolean", "ALLOW_BUILT_IN_CREDS", "false")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "ALLOW_BUILT_IN_CREDS", "true")
        }

        /**
         * perf：性能测试专用。
         *
         * debug 构建下 Compose 的滚动性能通常只有 release 的 1/3 —— R8 未开、
         * 方法未内联、强跳过（strongSkipping）关闭。海报墙实测 84% 掉帧、
         * UI 线程中位 53ms，与「debug 构建的 Compose 网格」特征吻合。
         *
         * 本构建类型：
         * - 开启 R8（minify + shrinkResources），拿到接近 release 的代码质量
         * - 保留 .debug 后缀 → 与已装的 debug 包签名/包名一致，可直接覆盖安装，
         *   登录态与本地数据全部保留
         * - 保留 BuildConfig.DEBUG = false（R8 会折叠内置凭据分支）
         *   → 因此显式注入内置凭据，避免每次都要手填 api_id
         */
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release", "debug")
            // 与 release 一样开 R8；保留映射文件便于看崩溃栈
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-perf.pro",
            )
            isDebuggable = true
            // 覆盖 release 的版本后缀，避免与 release 包冲突
            versionNameSuffix = "-perf"
            // perf 构建 BuildConfig.DEBUG=false，内置凭据分支会被 R8 折叠。
            // 这里单独注入开关，让 perf 包仍能零配置登录（仅本机调试用）。
            buildConfigField("boolean", "ALLOW_BUILT_IN_CREDS", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // 需要 BuildConfig.DEBUG：内置 api 凭据只在 debug 生效，
        // release 下该分支被 R8 折叠为 null，字面量随之被删除（不进 dex）。
        buildConfig = true
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)

    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    // JNA for libtdjson (td_json_client C interface)
    implementation("net.java.dev.jna:jna:5.15.0@aar")
}

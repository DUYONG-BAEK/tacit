import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.containsKey("storeFile")

android {
    namespace = "kr.co.snail.tacitq.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.co.snail.tacitq.mobile"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }

        // 프로파일 기본값 — 서버에서 내려받기 전 폴백으로 쓴다 (TB_PART_PROFILE)
        buildConfigField("int",    "DEFAULT_FRAMES_TARGET", "4")
        buildConfigField("int",    "DEFAULT_MIN_DWELL_MS",  "600")
        buildConfigField("String", "DEFAULT_AGREE_REVIEW",  "\"0.75\"")
        buildConfigField("int",    "DEFAULT_STORE_DAYS",    "30")
        buildConfigField("int",    "BITRATE_MIN_KBPS",      "6000")
        buildConfigField("String", "EDGE_BASE_URL",         "\"http://10.42.1.10:8080\"")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile     = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias      = keystoreProps["keyAlias"] as String
                keyPassword   = keystoreProps["keyPassword"] as String
                enableV2Signing = true; enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "TacitQMobile-${versionName}(${versionCode})-${name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    // C5 LocalStore 단위 시험(V-M04)을 Robolectric 으로 노트북에서 돌린다.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.vm)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.graphics)
    implementation(libs.androidx.compose.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)              // C2 엣지 WebSocket
    implementation(libs.webrtc)              // 실제 AI 엣지 WebRTC (설계서 §9)
    debugImplementation(libs.androidx.compose.tooling)

    // C5 LocalStore — Room(로컬 이력 + 전송 큐). 강제 종료 후 복구가 목적.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 상태 기계(C3) 단위 시험 V-M05 — 순수 JVM, 장비 없이 노트북에서 돈다
    testImplementation(libs.junit)
    // C5 LocalStore 단위 시험 V-M04 — Robolectric 으로 실제 Room 을 JVM 에서 검증
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

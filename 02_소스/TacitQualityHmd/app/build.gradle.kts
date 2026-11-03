plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "kr.co.snail.tacitq.hmd"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.co.snail.tacitq.hmd"
        minSdk = 29                 // RealWear Navigator 520 = Android 11(API 30)
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "VIDEO_MODE", "\"mjpeg\"")  // 2026-10-07 사용자 요청으로 MJPEG 720p 복귀. H.265: "hevc" (설계서 §8)
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "TacitQHmd-${versionName}(${versionCode})-${name}.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime)

    // CameraX — 촬영
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.kotlinx.coroutines)

    // BLE 쪽지(SHOW/ACK) 직렬화 — 모바일 ShowWire 와 같은 라이브러리
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

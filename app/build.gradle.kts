plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kakario.videoplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kakario.videoplayer"
        minSdk = 26
        targetSdk = 35
        // GitHub 자동 빌드 번호를 버전으로 사용 (새 APK가 기존 앱 위에 업데이트되도록)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // 갤럭시(64비트 ARM)용만 넣어 APK 크기를 줄인다
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // 저장소에 들어 있는 고정 디버그 키 — 빌드마다 서명이 바뀌어 재설치가 필요해지는 문제 방지
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // VLC와 FFmpeg 추출기가 같은 C++ 기본 라이브러리를 각자 들고 있어 하나만 넣는다
            pickFirsts += "**/libc++_shared.so"
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // FTP 접속 라이브러리 (Apache Commons Net)
    implementation("commons-net:commons-net:3.11.1")
    // 재생 엔진 (VLC) — MKV·AVI·AC3/DTS 음성·SMI/SRT/ASS 자막까지 폭넓게 지원
    implementation("org.videolan.android:libvlc-all:3.7.6")
    // 썸네일용 FFmpeg 추출기 — AVI·WMV·MPG·VOB·FLV·RMVB 등 안드로이드 기본 추출기가 못 여는 형식
    implementation("com.github.wseemann:FFmpegMediaMetadataRetriever-core:1.0.23")
    implementation("com.github.wseemann:FFmpegMediaMetadataRetriever-native-arm64-v8a:1.0.23")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

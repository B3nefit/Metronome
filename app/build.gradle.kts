plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stageclick.metronome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stageclick.metronome"
        minSdk = 26          // Android 8.0 이상 (저지연 AudioTrack API)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 개인 사용/사이드로딩용: 디버그 키로 서명해 바로 설치 가능한 APK를 만든다.
            // 스토어 배포 시에는 본인 키스토어로 바꾸세요.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 외부 라이브러리 없음 — 안드로이드 기본 API만 사용 (작고 빠르고 오프라인)
    testImplementation("junit:junit:4.13.2")
}

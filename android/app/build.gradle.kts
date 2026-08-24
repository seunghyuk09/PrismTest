plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 릴리스 키스토어는 저장소에 두지 않는다. CI 가 GitHub Secrets 에서 복원해
// 아래 환경변수로 넘긴다. 없으면 디버그 키로 서명하며, 그 사실을 릴리스 노트에 명시한다.
val releaseStorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() && File(it).exists() }

android {
    namespace = "com.prismtest.scope"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prismtest.scope"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("BUILD_NUMBER") ?: "0"}"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStorePath != null) {
            create("release") {
                storeFile = File(releaseStorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // debuggable=false 가 이 빌드의 핵심이다. 디버그 빌드는 다른 앱이
            // 프로세스에 붙어 메모리를 읽을 수 있고, Play Protect 도 그래서 막는다.
            isDebuggable = false
            // 축소·난독화는 아직 켜지 않는다. 실기 검증 전에 R8 이 무언가를 지우면
            // 원인 추적이 어려워진다. 앱이 폰에서 검증된 뒤 켠다.
            isMinifyEnabled = false
            signingConfig =
                if (releaseStorePath != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    dependenciesInfo {
        // APK 에 의존성 메타데이터 블록을 넣지 않는다(암호화된 불투명 블록이라
        // 제3자가 내용을 검증할 수 없다). 검증 가능성을 위해 뺀다.
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // 스윕 타이머가 delay() 를 쓴다. 전이 의존성에 기대지 않고 직접 선언한다.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
}

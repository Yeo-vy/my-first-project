plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.recorder.voicenote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.recorder.voicenote"
        // MediaRecorder.setNextOutputFile (조각 이어받기) 가 8.0 부터라 그 아래는 받지 않는다
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.0-web"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// 화면은 웹(WebView) 하나뿐이라 UI 라이브러리가 필요 없다.
// 남는 의존성은 '녹음 알림'과 '앱이 꺼진 뒤에도 이어지는 업로드' 둘뿐이다.
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    // 녹음이 끝난 파일을 서버로 올린다. 네트워크가 없으면 생길 때까지 기다렸다가 다시 시도한다.
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}

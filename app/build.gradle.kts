plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 빌드할 때마다 서명 키가 달라지면 폰이 덮어쓰기(업데이트)를 거부한다.
// 그래서 저장소에 고정 키를 하나 넣어두고 항상 그것으로 서명한다.
val fixedKeystore = rootProject.file("keystore/sticky.jks")

// GitHub Actions 는 실행 번호를 넘겨준다. 빌드할 때마다 버전이 하나씩 올라가
// 폰이 "업데이트"로 인식한다. 로컬 빌드는 1 로 둔다.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.sticky.todo"
    compileSdk = 34

    signingConfigs {
        if (fixedKeystore.exists()) {
            create("app") {
                storeFile = fixedKeystore
                storePassword = "stickytodo"
                keyAlias = "sticky"
                keyPassword = "stickytodo"
            }
        }
    }

    defaultConfig {
        applicationId = "com.sticky.todo"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    buildTypes {
        getByName("debug") {
            if (fixedKeystore.exists()) {
                signingConfig = signingConfigs.getByName("app")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (fixedKeystore.exists()) {
                signingConfigs.getByName("app")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // 일부 CI 설정은 `gradlew build` 로 lint 까지 돌린다.
    // 경고 하나 때문에 APK 가 안 나오는 일이 없게 한다.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // 구글 로그인 (드라이브 접근 권한을 받기 위해)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    // 앱을 열지 않아도 도는 백그라운드 동기화
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}

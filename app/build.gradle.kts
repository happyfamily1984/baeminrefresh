plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.baeminrefresh.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.baeminrefresh.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"
    }

    flavorDimensions += "tier"
    productFlavors {
        // 지사 배포용: 재시작 기능만. 기존과 동일한 앱.
        create("rider") {
            dimension = "tier"
            applicationId = "com.baeminrefresh.app"
        }
        // 내 수입 기록까지 들어간 버전. 같은 applicationId/키라서 rider 위에 덮어 설치된다.
        create("pro") {
            dimension = "tier"
            applicationId = "com.baeminrefresh.app"
            versionNameSuffix = "-pro"
        }
    }

    // 릴리스 서명 키는 저장소에 두지 않고, CI가 GitHub Secret에서 꺼내 경로를 넘겨준다.
    // (키가 없으면 로컬 테스트용 기본 디버그 키로 서명)
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "baeminrefresh"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Firebase는 pro 버전에만 포함 (지사 배포용 rider에는 들어가지 않음)
    add("proImplementation", platform("com.google.firebase:firebase-bom:33.7.0"))
    add("proImplementation", "com.google.firebase:firebase-auth")
    add("proImplementation", "com.google.firebase:firebase-firestore")
}

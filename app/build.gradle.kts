import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 답변 추천(실험실)용 제미나이 API 키: GitHub Secret GEMINI_API_KEYS(여러 개는 줄바꿈·쉼표로 구분)를
// GEMINI_KEYS_PASSWORD 로 암호화해서 넣는다. APK 에는 암호문만 들어가고, 폰에서 비밀번호를 입력해야 풀린다.
// (형식: base64(salt 16 + iv 12 + AES-GCM 암호문) — ReplyAi.unlock 과 같아야 한다)
fun encryptedGeminiKeys(): String {
    val keys = (System.getenv("GEMINI_API_KEYS") ?: "").trim()
    val pass = (System.getenv("GEMINI_KEYS_PASSWORD") ?: "").trim()
    if (keys.isEmpty() || pass.isEmpty()) return ""
    val rnd = SecureRandom()
    val salt = ByteArray(16).also { rnd.nextBytes(it) }
    val iv = ByteArray(12).also { rnd.nextBytes(it) }
    val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(pass.toCharArray(), salt, 300_000, 256)).encoded
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    return Base64.getEncoder().encodeToString(salt + iv + cipher.doFinal(keys.toByteArray(Charsets.UTF_8)))
}

android {
    namespace = "com.baeminrefresh.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.baeminrefresh.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 64
        versionName = "1.33.0"
        buildConfigField("String", "GEMINI_KEYS_ENC", "\"${encryptedGeminiKeys()}\"")
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

    buildFeatures {
        buildConfig = true
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
}

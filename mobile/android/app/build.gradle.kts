plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "de.erika.mara"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "de.erika.mara"
        // Android 10+ (M-NF-04).
        minSdk = 29
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // Upload key for Google Play (MARA Stage 9): only on the release workflow, from its secrets
    // (.github/workflows/mobile-release.yml). Without them, release builds use the debug keys, so
    // `flutter run --release` and the size check in CI work; such builds can't be uploaded.
    val uploadKeystore = System.getenv("MARA_UPLOAD_KEYSTORE")
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("MARA_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MARA_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("MARA_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (uploadKeystore != null) "upload" else "debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

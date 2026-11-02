plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from the environment so no key material lives in the repository.
// Without TIVIMAN_KEYSTORE_PATH, assembleRelease still works and produces an unsigned APK.
val releaseKeystore: String? = System.getenv("TIVIMAN_KEYSTORE_PATH")

android {
    namespace = "faisalnh.tiviman"
    compileSdk = 35
    defaultConfig {
        applicationId = "faisalnh.tiviman"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-beta.1"
        testInstrumentationRunner = "faisalnh.tiviman.PrototypeChecks"
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("TIVIMAN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TIVIMAN_KEY_ALIAS")
                keyPassword = System.getenv("TIVIMAN_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
    // The dependency metadata block is encrypted with a Google key; FOSS repositories reject it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A fixed signing key means every new build installs right over the old one
// (no uninstall needed, so permissions and chat history are kept).
val fixedKey = rootProject.file("keystore/bubbles.keystore")
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.ershad.directbubbles"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ershad.directbubbles"
        minSdk = 29          // Android 10
        targetSdk = 35       // Android 15 rules; runs on Android 16/17/18+ too
        versionCode = runNumber
        versionName = "1.26.$runNumber"
    }

    signingConfigs {
        create("fixed") {
            storeFile = fixedKey
            storePassword = "bubbles"
            keyAlias = "bubbles"
            keyPassword = "bubbles"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            // R8 shrinking keeps the APK tiny (a few hundred KB).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/*.version", "/kotlin/**", "DebugProbesKt.bin")
        }
    }
}

// No libraries at all: only the Android framework + Kotlin standard library.
dependencies {
}

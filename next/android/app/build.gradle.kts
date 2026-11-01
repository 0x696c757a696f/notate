import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.notate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alexdremov.notate.next"
        minSdk = 26
        targetSdk = 36
        // CI passes -Pnotate.versionCode / -Pnotate.versionName (same scheme as the legacy app).
        versionCode = providers.gradleProperty("notate.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("notate.versionName").orNull ?: "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Shared debug key committed to the repo (not a secret) so CI and local APKs update in place.
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystoreFile = rootProject.file("keystore.properties")
            if (keystoreFile.exists()) {
                val properties = Properties()
                properties.load(FileInputStream(keystoreFile))
                storeFile = file(properties["storeFile"] as String)
                storePassword = properties["storePassword"] as String
                keyAlias = properties["keyAlias"] as String
                keyPassword = properties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(
                if (rootProject.file("keystore.properties").exists()) "release" else "debug",
            )
        }
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    buildFeatures { compose = true }
    kotlin { jvmToolchain(21) }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}

dependencies {
    implementation(project(":next:platform"))
    implementation(project(":next:render"))
    implementation(project(":next:editor"))
    implementation(project(":next:pdf"))
    implementation(project(":next:web"))
    implementation(project(":next:export"))
    implementation(project(":next:search"))
    implementation(project(":next:library"))
    testImplementation("junit:junit:4.13.2")
}

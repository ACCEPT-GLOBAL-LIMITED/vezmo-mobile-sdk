import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vezmo.example.nativeapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vezmo.example.nativeapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = providers.gradleProperty("VERSION_NAME").get()
        // Sandbox sessions from the dev demo endpoint (TEST mode only): no key in the app or the repo.
        buildConfigField("String", "VEZMO_DEMO_URL", "\"https://api.dev.vezmo.com/api/v1/demo/mobile-session\"")
        buildConfigField("String", "VEZMO_API_BASE", "\"https://api.dev.vezmo.com/api/v1\"")
    }

    buildTypes {
        release {
            // Signed with the debug key so CI can install it on an emulator; this app is never published.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":checkout"))
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

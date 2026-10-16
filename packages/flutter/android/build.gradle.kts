// The plugin's Android side: a thin bridge from Dart to the native Vezmo Android SDK (com.vezmo:vezmo-checkout),
// which draws the sheet.
group = "com.vezmo.flutter.checkout"
version = "0.1.0"

buildscript {
    val kotlinVersion = "2.4.0"
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.1.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        // Only for this repository's CI, which builds com.vezmo:vezmo-checkout into Maven local first
        // (ORG_GRADLE_PROJECT_vezmoMavenLocal=true). Apps get it from Maven Central.
        if (providers.gradleProperty("vezmoMavenLocal").isPresent) {
            mavenLocal { content { includeGroup("com.vezmo") } }
        }
    }
}

plugins {
    id("com.android.library")
}

android {
    namespace = "com.vezmo.flutter.checkout"

    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    defaultConfig {
        minSdk = 24
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // The native SDK, pinned exactly: the plugin's version and the SDK's move together.
    implementation("com.vezmo:vezmo-checkout:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

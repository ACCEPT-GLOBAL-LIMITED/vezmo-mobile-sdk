// The Vezmo Android SDK (`checkout`, published as com.vezmo:vezmo-checkout) and its example app.
// Build with Gradle 8.14 (CI pins it through setup-gradle; there is no wrapper in the repo).
plugins {
    id("com.android.library") version "8.11.1" apply false
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.1.20" apply false
    id("com.vanniktech.maven.publish") version "0.35.0" apply false
}

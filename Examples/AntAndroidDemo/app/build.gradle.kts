plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.25"
}

android {
    namespace = "com.autonomi.examples.antdemo"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.autonomi.examples.antdemo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // Snippet from the ant-android README.
    implementation(files("libs/ant-android-release.aar"))
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose UI.
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // WalletConnect spike — Reown AppKit (successor to Web3Modal), same stack
    // family as the desktop app. BOM 1.4.1 is the latest stable on Maven
    // Central (com.reown); artifacts resolve from the mavenCentral() already
    // declared in settings.gradle.kts.
    implementation(platform("com.reown:android-bom:1.4.1"))
    implementation("com.reown:android-core")
    implementation("com.reown:appkit")
    // The AppKit modal presents itself through Compose navigation.
    implementation("androidx.navigation:navigation-compose:2.8.0")
}

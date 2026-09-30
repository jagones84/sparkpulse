import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jagones.sparkpulse"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jagones.sparkpulse"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "1.6.11"

        // Build default token from a gitignored properties file; the secret
        // never lands in the repository (runtime override lives in SharedPreferences).
        val forgeProps = Properties().apply {
            runCatching { load(rootProject.file("local.forge.properties").inputStream()) }
        }
        val forgeToken = (forgeProps.getProperty("forgeToken") ?: "")
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "FORGE_TOKEN", "\"$forgeToken\"")
    }

    buildTypes {
        release { isMinifyEnabled = false }
        getByName("debug") { isDebuggable = true }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

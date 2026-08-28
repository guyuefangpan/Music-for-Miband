import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localSigningProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val starryStorePassword = localSigningProperties.getProperty("starry.storePassword")
    ?: System.getenv("STARRY_STORE_PASSWORD")
val starryKeyPassword = localSigningProperties.getProperty("starry.keyPassword")
    ?: System.getenv("STARRY_KEY_PASSWORD")
    ?: starryStorePassword
val starryKeyAlias = localSigningProperties.getProperty("starry.keyAlias")
    ?: System.getenv("STARRY_KEY_ALIAS")
    ?: "starry-test"

check(!starryStorePassword.isNullOrBlank()) {
    "Missing Starry signing password. Set starry.storePassword in local.properties or STARRY_STORE_PASSWORD."
}

android {
    namespace = "com.vt.starry"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.vt.starry"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        create("starry") {
            storeFile = rootProject.file("sign/starry-test.p12")
            storePassword = starryStorePassword
            keyAlias = starryKeyAlias
            keyPassword = starryKeyPassword
            storeType = "PKCS12"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("starry") }
        release { signingConfig = signingConfigs.getByName("starry") }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}

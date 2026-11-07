import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from key.properties in the project root, which is never committed
// (see README). Without it the release build is left unsigned, so a fresh clone still builds.
val keyProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.selbir.authenticator"
    compileSdk = 36

    defaultConfig {
        // Permanent once published on Google Play. It differs from the code namespace
        // above on purpose: the ID carries the product name, the code does not.
        applicationId = "tr.veilo.authenticator"
        // 30 is the first release where a Keystore key can require
        // "strong biometric OR device credential" (setUserAuthenticationParameters).
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.6"
    }

    signingConfigs {
        if (!keyProperties.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keyProperties.getProperty("storeFile"))
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Keeps Google's encrypted dependency blob out of the artifact, so the APK
    // contains nothing that cannot be reproduced from this repository.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // QR scanning without Google Play services: works offline and on de-Googled devices.
    implementation(libs.zxing.embedded)

    testImplementation(kotlin("test-junit"))
}

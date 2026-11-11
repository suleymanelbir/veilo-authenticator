import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from key.properties in the project root, which is never committed
// (see README). Without it the release build is left unsigned, so a fresh clone still builds.
fun signingProperties(name: String) = Properties().apply {
    val file = rootProject.file(name)
    if (file.exists()) file.inputStream().use(::load)
}
val keyProperties = signingProperties("key.properties")
// The Enterprise edition has its own upload key (Managed Google Play), so a leak of one key
// never touches the other app. Same rules: never committed; absent = unsigned release.
val enterpriseKeyProperties = signingProperties("key-enterprise.properties")

android {
    namespace = "com.selbir.authenticator"
    compileSdk = 36

    // Robolectric (store screenshots) needs the merged resources in unit tests.
    testOptions.unitTests.isIncludeAndroidResources = true

    defaultConfig {
        // Permanent once published on Google Play. It differs from the code namespace
        // above on purpose: the ID carries the product name, the code does not.
        applicationId = "tr.veilo.authenticator"
        // 30 is the first release where a Keystore key can require
        // "strong biometric OR device credential" (setUserAuthenticationParameters).
        minSdk = 30
        targetSdk = 36
        versionCode = 12
        versionName = "0.5.0"
    }

    signingConfigs {
        for ((name, props) in listOf("release" to keyProperties, "enterpriseRelease" to enterpriseKeyProperties)) {
            if (!props.isEmpty) {
                create(name) {
                    storeFile = rootProject.file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    // Two editions from one code base. "public" is the Play Store app: offline by construction,
    // no INTERNET permission. "enterprise" adds the Veilo Gateway client (enrolment confirm,
    // later attestation and challenge-response) and is distributed through Managed Google Play.
    flavorDimensions += "edition"
    productFlavors {
        create("public") {
            dimension = "edition"
            isDefault = true
        }
        create("enterprise") {
            dimension = "edition"
            applicationIdSuffix = ".enterprise"
            versionNameSuffix = "-enterprise"
        }
    }

    // release signing per edition (debug builds keep the debug key: the build type wins there)
    productFlavors.getByName("public").signingConfig = signingConfigs.findByName("release")
    productFlavors.getByName("enterprise").signingConfig = signingConfigs.findByName("enterpriseRelease")

    buildTypes {
        release {
            // per edition: the build type's signing config would override the flavours'
            signingConfig = null
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
        buildConfig = true
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

    // Play store screenshots rendered from the real composables (FLAG_SECURE blocks device
    // screenshots on purpose). Test-only: nothing here reaches a release build.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

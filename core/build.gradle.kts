import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Only used for Argon2id (backup key derivation). Everything else is JCA.
    implementation(libs.bouncycastle)
    // The backup payload is JSON.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit"))
}

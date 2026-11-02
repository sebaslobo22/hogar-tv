plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "live.hogar.airplay"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Sin dependencias externas de AirPlay ni de criptografía: SRP-6a, HKDF y
// ChaCha20-Poly1305 están implementados en Kotlin puro (ver Crypto.kt / Srp.kt).
dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

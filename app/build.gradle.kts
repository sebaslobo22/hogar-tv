import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Firma de publicación: si local.properties trae hogar.keystore/hogar.storepass se usa esa llave
// (estable entre versiones); si no, la de depuración, para que cualquiera pueda compilar.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "live.hogar.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "live.hogar"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        create("hogarRelease") {
            val ks = localProps.getProperty("hogar.keystore")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = localProps.getProperty("hogar.storepass")
                keyAlias = "hogar"
                keyPassword = localProps.getProperty("hogar.storepass")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("hogarRelease").takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":design"))
    implementation(project(":launcher"))
    implementation(project(":specs"))
    implementation(project(":airplay"))
    implementation(project(":player"))
    implementation(project(":receiver"))
    implementation(project(":browser"))
    implementation(project(":nowplaying"))
    implementation(project(":homekit"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
}

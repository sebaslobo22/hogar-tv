plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "live.hogar.design"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.tv.material)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// Tipografía: San Francisco si existe una copia local (uso doméstico; su licencia no permite
// redistribuirla, por eso fonts-local/ está en .gitignore), si no Inter (OFL, incluida).
android.sourceSets["main"].res.srcDir(
    if (rootProject.file("fonts-local/res/font/hogar_text_regular.ttf").exists()) rootProject.file("fonts-local/res")
    else file("src/inter/res"),
)

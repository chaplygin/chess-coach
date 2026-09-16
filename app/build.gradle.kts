plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.chesscoach.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.chesscoach.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-M0"

        // Телефон пользователя — arm64. Одна ABI держит APK минимальным,
        // а бинарник Stockfish (~76 МБ) добавится сюда же в M1.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")

    // Stockfish должен лежать на диске как обычный файл, иначе его нельзя запустить.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":coach"))
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.chesscoach.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.chesscoach.app"
        minSdk = 31
        targetSdk = 35
        // Номер сборки CI: растёт сам, и по нему приложение сравнивает себя с
        // последним релизом на GitHub. Локальная сборка считается самой старой.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"

        // Телефон пользователя — arm64. Одна ABI держит APK минимальным,
        // а бинарник Stockfish (~76 МБ) добавится сюда же в M1.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Android ставит обновление только поверх той же подписи. Отладочный ключ у
    // каждого раннера CI свой, поэтому без постоянного ключа каждая сборка
    // требовала бы удалить приложение вместе со входом и кэшем разборов.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    val keystorePassword = System.getenv("SIGNING_PASSWORD")
    val fixedSigning = if (keystorePath != null && keystorePassword != null) {
        signingConfigs.create("fixed") {
            storeFile = file(keystorePath)
            storePassword = keystorePassword
            keyAlias = "chesscoach"
            keyPassword = keystorePassword
            storeType = "pkcs12"
        }
    } else {
        null
    }

    buildTypes {
        debug {
            fixedSigning?.let { signingConfig = it }
        }
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
    implementation(project(":lichess"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // LocalLifecycleOwner из compose.ui.platform объявлен устаревшим и переехал
    // сюда: живой экран обязан опрашивать Lichess только пока он на виду, и
    // держаться на устаревшем API ради экономии одной зависимости не стоит.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Кэш разбора партии (filesDir/analysis/{gameId}.json) — тот же формат,
    // что уже используется для ответов Lichess в :lichess.
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
}

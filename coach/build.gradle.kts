plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    // Только для чтения файла с задачами Lichess в локальной упряжи разбора.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnit() }

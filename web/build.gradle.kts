import org.teavm.gradle.api.JSModuleType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.teavm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":coach"))
    implementation(libs.teavm.jso)
}

// TeaVM переводит байткод :core и :coach в JavaScript. Так браузер исполняет тот
// же самый код тренера, что и телефон, а не его переписанную копию, которая
// рано или поздно разошлась бы с оригиналом.
teavm {
    js {
        mainClass = "org.chesscoach.web.WebCoach"
        moduleType = JSModuleType.ES2015
        targetFileName = "coach.js"
        obfuscated = true
    }
}

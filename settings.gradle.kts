pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // chesslib нет на Maven Central, она публикуется только через JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ChessCoach"
include(":app")
include(":core")
include(":coach")
include(":engine")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\.android.*")
                includeGroupByRegex("com\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // chesslib is NOT on Maven Central - it is only published via JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ChessCoach"
include(":app")
include(":core")
include(":coach")

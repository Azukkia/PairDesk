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
    }
}

rootProject.name = "PairDesk"

// :core is the pure Kotlin/JVM protocol implementation; the Android
// application module (:app) is included once its directory exists.
include(":core")
if (file("app/build.gradle.kts").exists()) include(":app")

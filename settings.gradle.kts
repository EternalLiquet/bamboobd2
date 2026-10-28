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

rootProject.name = "bamboobd2"

// The portable core is a standalone build, so `./gradlew -p core check` never
// configures :app or resolves the Android Gradle Plugin.
includeBuild("core")
include(":app")

import org.gradle.api.initialization.resolve.RepositoriesMode

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
        maven(url = "https://mvn.breez.technology/releases")
        maven(url = "https://raw.githubusercontent.com/guardianproject/gpmaven/master")
    }
}

rootProject.name = "Split Android"
include(":app")

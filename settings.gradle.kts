// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
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

rootProject.name = "cubexcraft-android"
include(":core")

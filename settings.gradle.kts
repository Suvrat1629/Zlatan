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

rootProject.name = "sih-26168-app"

include(":core-types")
include(":core-model")
include(":core-nav")
include(":core-map")
include(":core-assets")
include(":core-replay")
include(":engine")

include(":android-sensors")
include(":android-assets")
include(":android-model")
include(":app")

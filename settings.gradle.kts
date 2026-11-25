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
rootProject.name = "LocationRecoveryLab"
include(":app", ":core:map", ":core:location", ":core:simulation", ":benchmark", ":feature:lab", ":ui:map")
include(":live", ":route-lab")

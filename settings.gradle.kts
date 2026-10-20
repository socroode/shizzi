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

rootProject.name = "Shizzi"
if (providers.gradleProperty("shizziAccountsOnly").orNull == "true") {
    include(":accounts")
} else {
    if (providers.gradleProperty("shizziClientsOnly").orNull != "true") include(":app")
    include(":conso")
    include(":admin")
}

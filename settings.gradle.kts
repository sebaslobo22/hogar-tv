pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Hogar"
include(":app", ":core", ":design", ":launcher", ":specs", ":airplay", ":player", ":receiver", ":browser", ":nowplaying", ":homekit")

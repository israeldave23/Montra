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
        // GitHub-hosted runners have received rate limits from the primary Central endpoint.
        maven("https://maven-central.storage-download.googleapis.com/maven2") {
            name = "MavenCentralMirror"
        }
    }
}

rootProject.name = "Montra"
include(":app")

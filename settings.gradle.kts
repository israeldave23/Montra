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
        exclusiveContent {
            // Use Google's Maven Central mirror for the large Robolectric SDK jars.
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2") {
                    name = "RobolectricSdkMirror"
                }
            }
            filter { includeModule("org.robolectric", "android-all-instrumented") }
        }
    }
}

rootProject.name = "Montra"
include(":app")

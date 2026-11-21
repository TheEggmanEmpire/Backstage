// Standalone build of the:backstage library (fast dev loop + unit tests).
// Inside the full app build the same module is included by upstream Dextop's
// android/settings.gradle.kts (patch 0001), which supplies the plugin versions.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "backstage-standalone"
include(":backstage")
project(":backstage").projectDir = file("android")

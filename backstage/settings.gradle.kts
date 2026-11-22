// Standalone Gradle build for the Android library and companion.
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
include(":companion")

plugins {
    // Lets Gradle download JDK 21 automatically if a teammate doesn't have it installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "sentence-builder"

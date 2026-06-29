pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "vaultdoc-backend"

include(
    "core-auth",
    "core-crypto",
    "core-pipeline",
    "core-storage",
    "ingest",
    "ocr",
    "extraction",
    "search",
    "catalog",
    "alerts",
    "sharing",
    "assistant",
    "inbox",
    "household",
    "billing",
    "api-gateway",
)

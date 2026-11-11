pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Automatic download of the JDK 25 toolchain the build requires.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Maven Central (Central Portal) publishing: one aggregated deployment for the whole build.
    id("com.gradleup.nmcp.settings") version "1.6.2"
}

rootProject.name = "config"

// Bill of materials: aligns every published config-* module.
include("config-bom")

// Core domain: applications, environments, keys, values, versions. No I/O.
include("config-model")

// Datastore SPI plus one module per backend. Backends are found via ServiceLoader.
include("config-datastore")
include("config-datastore-postgresql")
include("config-datastore-dynamodb")
include("config-datastore-etcd")
// Contract tests every backend must pass (published as test fixtures for third-party backends).
include("config-datastore-testkit")

// In-process library: reads a datastore directly, with caching and refresh.
include("config-library")

// Java client for the config service's HTTP API (openapi/config-api.yaml).
include("config-client")

// The config service itself.
include("config-server")

// End-to-end tests against real datastores (Testcontainers). Not published.
include("config-integ")

nmcpSettings {
    centralPortal {
        username = System.getenv("CENTRAL_PORTAL_USERNAME")
            ?: providers.gradleProperty("centralPortalUsername").orNull
        password = System.getenv("CENTRAL_PORTAL_PASSWORD")
            ?: providers.gradleProperty("centralPortalPassword").orNull
    }
}

/*
 * The released version comes from a vX.Y.Z git tag on HEAD; otherwise gradle.properties' SNAPSHOT.
 * beforeProject rather than allprojects in the root build, which would run after this and win.
 */
gradle.beforeProject {
    val tag = providers.exec {
        commandLine("git", "describe", "--tags", "--exact-match", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()

    if (tag.startsWith("v")) {
        val semver = Regex("^v(\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.]+)?)$").matchEntire(tag)
        if (semver != null) {
            version = semver.groupValues[1]
            if (this == rootProject) {
                logger.lifecycle("Using version from Git tag: $version")
            }
        } else if (this == rootProject) {
            logger.warn("Git tag '$tag' is not vX.Y.Z; keeping the version from gradle.properties")
        }
    }
}

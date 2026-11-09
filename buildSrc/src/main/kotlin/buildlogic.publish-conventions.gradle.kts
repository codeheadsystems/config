/*
 * Maven Central publication metadata. Applied per module: publishing is a decision about an
 * artifact, not a property of being in this build. The upload itself is owned by the nmcp settings
 * plugin (settings.gradle.kts), which aggregates every publishing module into ONE deployment.
 */
plugins {
    `maven-publish`
    signing
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            pom {
                name.set(project.name)
                // A provider: the module's `description = ...` line runs after this plugin applies.
                description.set(provider {
                    project.description ?: "Configuration service and libraries"
                })
                url.set("https://github.com/codeheadsystems/config")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("wolpert")
                        name.set("Ned Wolpert")
                        email.set("ned.wolpert@gmail.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/codeheadsystems/config.git")
                    developerConnection.set("scm:git:ssh://git@github.com:codeheadsystems/config.git")
                    url.set("https://github.com/codeheadsystems/config")
                }
            }
        }
    }
    // No `repositories` block: nmcp owns the upload to the Central Portal.
}

val publication = publishing.publications.named<MavenPublication>("mavenJava")
pluginManager.withPlugin("java") { publication.configure { from(components["java"]) } }
pluginManager.withPlugin("java-platform") {
    publication.configure { from(components["javaPlatform"]) }
}

afterEvaluate {
    check(pluginManager.hasPlugin("java") || pluginManager.hasPlugin("java-platform")) {
        "${project.path} applies buildlogic.publish-conventions but neither `java` nor " +
            "`java-platform`, so it has nothing to publish"
    }
}

signing {
    // Required only for a real (non-SNAPSHOT) release on a machine that has a key.
    val credentialsPresent = project.hasProperty("signing.gnupg.keyName")
        || System.getenv("GPG_KEY_ID") != null
    isRequired = credentialsPresent && !version.toString().endsWith("SNAPSHOT")
    useGpgCmd()
    sign(publishing.publications["mavenJava"])
}

// isRequired=false still runs gpg with useGpgCmd(); skip the task so keyless local publishes work.
tasks.withType<Sign>().configureEach {
    onlyIf { signing.isRequired }
}

tasks.register("verifyPublishConfig") {
    val coordinates = "${project.group}:${project.name}:${project.version}"
    val snapshot = project.version.toString().endsWith("SNAPSHOT")
    val signed = signing.isRequired
    doLast {
        println("$coordinates  snapshot=$snapshot signed=$signed")
    }
}

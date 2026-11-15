/*
 * Bill of materials: no code, one POM. Import it to align every config-* module at one version.
 */
plugins {
    `java-platform`
    id("buildlogic.publish-conventions")
}

description = "BOM aligning all published config-* modules"

// nmcp resolves its task jar from this project's repositories.
repositories {
    mavenCentral()
}

dependencies {
    constraints {
        // Only modules that are published. The etcd backend, a placeholder, joins when it is
        // implemented; config-server is an application, not a library, so it never does.
        api(project(":config-client"))
        api(project(":config-model"))
        api(project(":config-datastore"))
        api(project(":config-datastore-dynamodb"))
        api(project(":config-datastore-s3"))
        api(project(":config-datastore-sql"))
        api(project(":config-datastore-testkit"))
        api(project(":config-library"))
    }
}

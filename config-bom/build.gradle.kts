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
        api(project(":config-model"))
        api(project(":config-datastore"))
        api(project(":config-datastore-postgresql"))
        api(project(":config-datastore-dynamodb"))
        api(project(":config-datastore-etcd"))
        api(project(":config-datastore-testkit"))
        api(project(":config-yaml"))
        api(project(":config-library"))
        api(project(":config-client"))
    }
}

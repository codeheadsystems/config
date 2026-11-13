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
        // Only modules that are published. Placeholders (config-library, config-client and the
        // postgresql, dynamodb and etcd backends) join when they are implemented.
        api(project(":config-model"))
        api(project(":config-datastore"))
        api(project(":config-datastore-s3"))
        api(project(":config-datastore-testkit"))
    }
}

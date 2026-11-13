plugins {
    id("buildlogic.java-common-conventions")
}

description = "End-to-end tests against real datastores"

dependencies {
    testImplementation(project(":config-server"))
    testImplementation(project(":config-client"))
    testImplementation(project(":config-library"))
    testImplementation(project(":config-datastore-postgresql"))
    testImplementation(project(":config-datastore-dynamodb"))
    testImplementation(project(":config-datastore-etcd"))
    testImplementation(project(":config-datastore-s3"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

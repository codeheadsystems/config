plugins {
    id("buildlogic.java-library-conventions")
    // Not published yet: this module is a placeholder. Add buildlogic.publish-conventions (and a
    // constraint in config-bom) when it has a real implementation.
}

description = "PostgreSQL datastore backend"

dependencies {
    api(project(":config-datastore"))
    implementation(libs.bundles.jdbi)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.pgjdbc)

    testImplementation(project(":config-datastore-testkit"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.pgjdbc)
}

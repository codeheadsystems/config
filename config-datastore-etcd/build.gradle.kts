plugins {
    id("buildlogic.java-library-conventions")
    // Not published yet: this module is a placeholder. Add buildlogic.publish-conventions (and a
    // constraint in config-bom) when it has a real implementation.
}

description = "etcd datastore backend"

dependencies {
    api(project(":config-datastore"))
    implementation(libs.jetcd.core)

    testImplementation(project(":config-datastore-testkit"))
}

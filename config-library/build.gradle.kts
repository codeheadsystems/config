plugins {
    id("buildlogic.java-library-conventions")
    // Not published yet: this module is a placeholder. Add buildlogic.publish-conventions (and a
    // constraint in config-bom) when it has a real implementation.
}

description = "In-process configuration library that reads a datastore directly"

dependencies {
    api(project(":config-model"))
    api(project(":config-datastore"))

    testImplementation(libs.bundles.test)
}

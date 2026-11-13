plugins {
    id("buildlogic.java-library-conventions")
    // Not published yet: this module is a placeholder. Add buildlogic.publish-conventions (and a
    // constraint in config-bom) when it has a real implementation.
}

description = "Java client for the config service HTTP API"

dependencies {
    api(project(":config-model"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}

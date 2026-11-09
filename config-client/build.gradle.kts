plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Java client for the config service HTTP API"

dependencies {
    api(project(":config-model"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}

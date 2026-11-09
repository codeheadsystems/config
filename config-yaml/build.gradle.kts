plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "YAML import and export of application configuration"

dependencies {
    api(project(":config-model"))
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
}

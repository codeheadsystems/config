plugins {
    id("buildlogic.java-common-conventions")
    `java-library`
    // Not published: test infrastructure only, so no buildlogic.publish-conventions and no BOM entry.
}

description = "In-process OpenID Connect issuer for tests"

dependencies {
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)
}

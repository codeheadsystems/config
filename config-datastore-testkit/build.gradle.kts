plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Contract tests that every datastore backend must pass"

dependencies {
    api(project(":config-datastore"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(libs.assertj)
}

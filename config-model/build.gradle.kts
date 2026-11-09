plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Core configuration domain model: applications, environments, keys and values"

dependencies {
    api(libs.slf4j.api)
}

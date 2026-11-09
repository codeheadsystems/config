plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "In-process configuration library that reads a datastore directly"

dependencies {
    api(project(":config-model"))
    api(project(":config-datastore"))
}

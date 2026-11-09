plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Datastore SPI that every config-datastore-* backend implements"

dependencies {
    api(project(":config-model"))
}

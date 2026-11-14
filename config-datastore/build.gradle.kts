plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Datastore SPI that every config-datastore-* backend implements"

dependencies {
    api(project(":config-model"))
    // Standard JSR-330 annotations, so any container (Dagger, Spring, Guice...) can build the
    // datastores. No container is pulled in; the application chooses one.
    api(libs.jakarta.inject)
}

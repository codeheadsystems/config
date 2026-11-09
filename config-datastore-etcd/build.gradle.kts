plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "etcd datastore backend"

dependencies {
    api(project(":config-datastore"))
    implementation(libs.jetcd.core)

    testImplementation(project(":config-datastore-testkit"))
}

plugins {
    id("buildlogic.java-application-conventions")
}

description = "The config service: serves configuration over HTTP from a datastore"

dependencies {
    implementation(project(":config-model"))
    implementation(project(":config-datastore"))
    implementation(project(":config-yaml"))

    // Backends are selected at runtime through ServiceLoader; ship all of them.
    runtimeOnly(project(":config-datastore-postgresql"))
    runtimeOnly(project(":config-datastore-dynamodb"))
    runtimeOnly(project(":config-datastore-etcd"))

    testImplementation(libs.bundles.test)
}

application {
    mainClass = "com.codeheadsystems.config.server.ConfigServer"
}

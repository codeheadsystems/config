plugins {
    id("buildlogic.java-application-conventions")
}

description = "The config service: serves configuration over HTTP from a datastore"

dependencies {
    implementation(project(":config-model"))
    implementation(project(":config-datastore"))

    implementation(platform(libs.dropwizard.bom))
    implementation(libs.dropwizard.core)
    implementation(libs.dagger)
    annotationProcessor(libs.dagger.compiler)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.caffeine)

    // Backends are selected at runtime through ServiceLoader; ship all of them.
    runtimeOnly(project(":config-datastore-sql"))
    runtimeOnly(project(":config-datastore-dynamodb"))
    runtimeOnly(project(":config-datastore-etcd"))
    runtimeOnly(project(":config-datastore-s3"))

    testImplementation(libs.bundles.test)
    testImplementation(libs.dropwizard.testing)
    testImplementation(project(":config-test-oidc"))
    // The Java client generated from the spec, to check the server against what clients expect.
    testImplementation(project(":config-client"))
}

tasks.withType<JavaCompile>().configureEach {
    // JAX-RS, Jackson and Dropwizard annotations are read at runtime, not by an annotation
    // processor; without this, -Xlint:all reports them as unclaimed and -Werror fails the build.
    options.compilerArgs.add("-Xlint:-processing")
}

application {
    mainClass = "com.codeheadsystems.config.server.ConfigServerApplication"
}

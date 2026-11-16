plugins {
    id("buildlogic.java-common-conventions")
}

description = "End-to-end tests against real datastores"

dependencies {
    testImplementation(project(":config-server"))
    testImplementation(project(":config-client"))
    testImplementation(project(":config-library"))
    testImplementation(project(":config-datastore-sql"))
    testImplementation(project(":config-datastore-dynamodb"))
    testImplementation(project(":config-datastore-etcd"))
    testImplementation(project(":config-datastore-s3"))
    testImplementation(project(":config-datastore-testkit"))
    testImplementation(project(":config-test-oidc"))
    testImplementation(platform(libs.dropwizard.bom))
    testImplementation(libs.dropwizard.testing)
    // To create the S3 test bucket; the server itself only reads it.
    testImplementation(platform(libs.aws.sdk.bom))
    testImplementation(libs.aws.sdk.s3)
    testImplementation(platform(libs.jackson.bom))
    testImplementation(libs.jackson.databind)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

/*
 * The Rust client runs in these tests too, through rust/config-integ-driver: a small program the
 * Java tests start against the real server. It needs cargo. Without cargo the Rust tests skip on a
 * developer machine and fail on CI (see TestEnvironment), like the Docker-based tests.
 */
val rustWorkspace = rootProject.layout.projectDirectory.dir("rust")
val cargo = providers.environmentVariable("PATH").map { path ->
    path.split(File.pathSeparator).map { File(it, "cargo") }.firstOrNull { it.canExecute() }?.path ?: ""
}

val buildRustDriver = tasks.register<Exec>("buildRustDriver") {
    description = "Builds rust/config-integ-driver, which runs the Rust client in the end-to-end tests."
    onlyIf("cargo is on the PATH") { cargo.get().isNotEmpty() }
    workingDir = rustWorkspace.asFile
    executable = cargo.getOrElse("cargo")
    args("build", "--locked", "-p", "config-integ-driver")
}

tasks.test {
    if (cargo.get().isNotEmpty()) {
        dependsOn(buildRustDriver)
        val driver = rustWorkspace.file("target/debug/config-integ-driver").asFile
        inputs.file(driver).withPropertyName("rustDriver")
        systemProperty("config.integ.rustDriver", driver.path)
    }
}

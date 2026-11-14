plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "SQL datastore backend (PostgreSQL), built on JDBI SqlObjects and Flyway migrations"

dependencies {
    api(project(":config-datastore"))
    // Callers construct the datastore from their own Jdbi, so Jdbi is part of this module's API.
    api(libs.jdbi.core)
    // Standard JSR-330 annotations only; the caller picks the container (Dagger, Spring, Guice...).
    api(libs.jakarta.inject)
    implementation(libs.jdbi.sqlobject)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.pgjdbc)

    testImplementation(project(":config-datastore-testkit"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
}

// jdbi's class files carry SpotBugs and Error Prone annotations whose jars it does not ship; javac
// warns about each missing one ([classfile]), which -Werror turns into a failure. Nothing else.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}

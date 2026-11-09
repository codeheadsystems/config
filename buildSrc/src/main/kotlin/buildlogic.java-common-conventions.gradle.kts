/*
 * Shared Java settings for every JVM module.
 *
 * Built WITH JDK 25, compatible WITH Java 21: the toolchain is 25, but every compile targets
 * `--release 21`, so the class files (major version 65) and the API surface they link against are
 * Java 21's. Using a Java 22+ API fails compilation rather than failing at a consumer's runtime.
 */
plugins {
    java
    jacoco
}

repositories {
    mavenCentral()
}

// Type-safe `libs` accessors are not generated for precompiled script plugins; use the API.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findBundle("test").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addBooleanOption("html5", true)
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    id("buildlogic.java-library-conventions")
    alias(libs.plugins.openapi.generator)
    id("buildlogic.publish-conventions")
}

description = "Java client for the config service HTTP API"

val spec = rootProject.layout.projectDirectory.file("openapi/config-api.yaml")
val generatedDir = layout.buildDirectory.dir("generated/openapi")

/*
 * The HTTP layer (com.codeheadsystems.config.client.{api,model,invoker}) is generated from the
 * spec on every build: never edited, never committed. Generation also validates the spec.
 */
val generateJavaClient by tasks.registering(GenerateTask::class) {
    group = "build"
    description = "Generates the Java HTTP client from openapi/config-api.yaml."
    generatorName = "java"
    library = "native" // java.net.http: no HTTP library dependency
    inputSpec = spec
    outputDir = generatedDir
    apiPackage = "com.codeheadsystems.config.client.api"
    modelPackage = "com.codeheadsystems.config.client.model"
    invokerPackage = "com.codeheadsystems.config.client.invoker"
    // "Error" would shadow java.lang.Error wherever the model package is imported.
    modelNameMappings = mapOf("Error" to "ErrorMessage")
    validateSpec = true
    cleanupOutput = true // no stale classes after an operation or schema is renamed
    generateApiTests = false
    generateModelTests = false
    generateApiDocumentation = false
    generateModelDocumentation = false
    configOptions = mapOf(
        "useJakartaEe" to "true",
        // Jackson 3 (tools.jackson), like the rest of the project. config-server stays on
        // Jackson 2 only because Dropwizard does.
        "useJackson3" to "true",
        "openApiNullable" to "false",
        "hideGenerationTimestamp" to "true",
        "dateLibrary" to "java8",
        "sourceFolder" to "src/main/java",
    )
}

// Generated code gets its own source set, so the generator's warnings do not have to be allowed
// in hand-written code: main keeps -Xlint:all -Werror.
val generated: SourceSet by sourceSets.creating {
    // From the task's output, so everything reading these sources depends on generateJavaClient.
    java.srcDir(generateJavaClient.flatMap { it.outputDir.dir("src/main/java") })
}

tasks.named<JavaCompile>(generated.compileJavaTaskName) {
    options.compilerArgs.removeAll { it == "-Werror" || it.startsWith("-Xlint") }
    options.compilerArgs.add("-Xlint:none")
}

tasks.jar { from(generated.output) }
tasks.named<Jar>("sourcesJar") { from(generated.allSource) }

dependencies {
    // The generated client's public API exposes Jackson 3 (its ObjectMapper) and jakarta
    // annotations. Jackson 3 handles java.time itself; there is no separate JSR-310 module.
    val generatedApi = listOf(libs.jackson.databind, libs.jakarta.annotation)
    "generatedImplementation"(platform(libs.jackson.bom))
    generatedApi.forEach { "generatedImplementation"(it) }

    api(project(":config-model"))
    api(files(generated.output))
    api(platform(libs.jackson.bom))
    generatedApi.forEach { api(it) }
}

/*
 * The Rust HTTP layer, the config-api crate in the rust/ workspace. Cargo cannot run the
 * generator, so the crate is committed: regenerate it with generateRustClient after changing the
 * spec. checkRustClient (part of check) fails the build if the committed crate is not exactly
 * what the spec generates.
 */
val rustCrate = rootProject.layout.projectDirectory.dir("rust/config-api")

/*
 * The generator's Rust (reqwest) client drops response headers, and the API carries the version
 * in them (HEAD answers with headers only). This patches two of its built-in templates, read from
 * the generator jar, so every response, success or error, carries its status and headers
 * (ResponseContent.headers). Each edit must apply exactly as often as expected: after a
 * generator upgrade that changes these templates, the build fails here instead of silently
 * generating a client without headers.
 */
val rustTemplates = layout.buildDirectory.dir("rust-client-templates")

val patchRustTemplates by tasks.registering {
    description = "Patches the generator's Rust templates so responses carry their headers."
    val generatorVersion = libs.versions.openapiGenerator.get()
    val out = rustTemplates
    inputs.property("generatorVersion", generatorVersion)
    outputs.dir(out)
    doLast {
        // path is under the generator's rust/ templates, such as "reqwest/api.mustache".
        fun builtin(path: String): String =
            GenerateTask::class.java.classLoader.getResource("rust/$path")?.readText()
                ?: throw GradleException("openapi-generator $generatorVersion has no rust/$path template")
        fun String.patch(name: String, old: String, new: String, times: Int): String {
            val found = split(old).size - 1
            if (found != times) {
                throw GradleException("openapi-generator $generatorVersion: expected '$old' $times time(s) in " +
                    "rust/$name, found $found. Update patchRustTemplates for this generator version.")
            }
            return replace(old, new)
        }
        // Generated code is never edited, so it is not linted: CI runs clippy with -D warnings.
        File(out.get().asFile.apply { mkdirs() }, "lib.mustache").writeText(builtin("lib.mustache")
            .patch("lib.mustache", "#![allow(clippy::too_many_arguments)]\n", "#![allow(clippy::all)]\n", 1))
        val dir = out.get().dir("reqwest").asFile.apply { mkdirs() }
        File(dir, "api_mod.mustache").writeText(builtin("reqwest/api_mod.mustache").patch("reqwest/api_mod.mustache",
            "    pub status: reqwest::StatusCode,\n",
            "    pub status: reqwest::StatusCode,\n    pub headers: reqwest::header::HeaderMap,\n", 1))
        File(dir, "api.mustache").writeText(builtin("reqwest/api.mustache")
            .patch("reqwest/api.mustache", "    let status = resp.status();\n",
                "    let status = resp.status();\n    let headers = resp.headers().clone();\n", 1)
            .patch("reqwest/api.mustache", "ResponseContent { status, content, entity }",
                "ResponseContent { status, headers, content, entity }", 2))
    }
}

fun GenerateTask.rustClient(output: Provider<Directory>) {
    dependsOn(patchRustTemplates)
    generatorName = "rust"
    library = "reqwest"
    templateDir = rustTemplates
    inputSpec = spec
    outputDir = output
    packageName = "config-api"
    configOptions = mapOf(
        "packageVersion" to "0.1.0",
        // Every success returns ResponseContent (status, headers, body), so 200 and 304 differ.
        "supportMultipleResponses" to "true",
    )
    modelNameMappings = mapOf("Error" to "ErrorMessage")
    validateSpec = true
    generateApiDocumentation = false
    generateModelDocumentation = false
}

val generateRustClient by tasks.registering(GenerateTask::class) {
    group = "build"
    description = "Regenerates rust/config-api from openapi/config-api.yaml."
    rustClient(provider { rustCrate })
    // Remove what a previous spec generated, so renamed operations leave nothing behind.
    doFirst { delete(rustCrate.dir("src"), rustCrate.dir(".openapi-generator")) }
}

val rustCheckDir = layout.buildDirectory.dir("rust-client-check")

val generateRustClientForCheck by tasks.registering(GenerateTask::class) {
    rustClient(rustCheckDir)
    mustRunAfter(generateRustClient) // both read rust/config-api; check what was just generated
    // Start empty, with the crate's hand-written files: .openapi-generator-ignore, which the
    // generator only reads from its output directory, and rustfmt.toml, which it never writes.
    val handWritten = listOf(".openapi-generator-ignore", "rustfmt.toml").map { rustCrate.file(it) }
    inputs.files(handWritten)
    doFirst {
        val dir = rustCheckDir.get().asFile
        delete(dir)
        dir.mkdirs()
        handWritten.forEach { it.asFile.copyTo(File(dir, it.asFile.name)) }
    }
}

val checkRustClient by tasks.registering {
    group = "verification"
    description = "Fails if rust/config-api is not what openapi/config-api.yaml generates."
    dependsOn(generateRustClientForCheck)
    mustRunAfter(generateRustClient)
    val expected = rustCheckDir
    val actual = rustCrate
    inputs.dir(expected)
    inputs.dir(actual)
    doLast {
        fun files(root: File) = root.walkTopDown()
            .filter { it.isFile && !it.path.contains("/target/") }
            .associate { it.relativeTo(root).path to it.readText() }
        val want = files(expected.get().asFile)
        val have = files(actual.asFile)
        val stale = (want.keys + have.keys).filter { want[it] != have[it] }.sorted()
        if (stale.isNotEmpty()) {
            throw GradleException(
                "rust/config-api does not match openapi/config-api.yaml; run " +
                    "./gradlew :config-client:generateRustClient and commit the result. Differs: $stale")
        }
    }
}

tasks.check { dependsOn(checkRustClient) }

plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Core configuration domain model: applications, environments, keys and values"

dependencies {
    api(libs.slf4j.api)
}

// config-model is the API every application codes against: with -Werror, a public or protected
// element without Javadoc fails the build here (other modules only check the Javadoc they have).
tasks.compileJava {
    options.compilerArgs.add("-Xdoclint:all/protected")
}

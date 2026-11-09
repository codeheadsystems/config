/*
 * A published Java library: java-library plus sources and javadoc jars (Maven Central requires both).
 */
plugins {
    id("buildlogic.java-common-conventions")
    `java-library`
}

java {
    withJavadocJar()
    withSourcesJar()
}

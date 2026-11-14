plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Amazon DynamoDB datastore backend"

dependencies {
    api(project(":config-datastore"))
    // Callers construct the datastore from their own DynamoDbClient, so it is part of the API.
    api(platform(libs.aws.sdk.bom))
    api(libs.aws.sdk.dynamodb)
    // Standard JSR-330 annotations only; the caller picks the container (Dagger, Spring, Guice...).
    api(libs.jakarta.inject)

    testImplementation(project(":config-datastore-testkit"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
}

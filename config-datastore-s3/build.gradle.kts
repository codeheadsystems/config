plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Amazon S3 datastore backend"

dependencies {
    api(project(":config-datastore"))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3)
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind)

    testImplementation(project(":config-datastore-testkit"))
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
}

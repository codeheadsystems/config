plugins {
    id("buildlogic.java-library-conventions")
    // Not published yet: this module is a placeholder. Add buildlogic.publish-conventions (and a
    // constraint in config-bom) when it has a real implementation.
}

description = "Amazon DynamoDB datastore backend"

dependencies {
    api(project(":config-datastore"))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.dynamodb)

    testImplementation(project(":config-datastore-testkit"))
}

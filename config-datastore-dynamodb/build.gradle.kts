plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Amazon DynamoDB datastore backend"

dependencies {
    api(project(":config-datastore"))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.dynamodb)

    testImplementation(project(":config-datastore-testkit"))
}

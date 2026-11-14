/*
 * Root build. group/version come from gradle.properties (overridden by a git tag in
 * settings.gradle.kts), so they are deliberately not set here.
 *
 *   ./gradlew build                              build and test everything
 *   ./gradlew testCodeCoverageReport             aggregated coverage
 *   ./gradlew publishAggregationToCentralPortal  release (CI only; see RELEASING.md)
 */
plugins {
    id("jacoco-report-aggregation")
}

repositories {
    mavenCentral()
}

dependencies {
    listOf(
        ":config-model",
        ":config-datastore",
        ":config-datastore-sql",
        ":config-datastore-dynamodb",
        ":config-datastore-etcd",
        ":config-datastore-s3",
        ":config-datastore-testkit",
        ":config-library",
        ":config-client",
        ":config-server",
        ":config-integ",
    ).forEach { jacocoAggregation(project(it)) }
}

reporting {
    reports {
        create<JacocoCoverageReport>("testCodeCoverageReport") {
            testSuiteName = "test"
        }
    }
}

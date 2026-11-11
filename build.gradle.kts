/*
 * Root build. group/version come from gradle.properties (overridden by a git tag in
 * settings.gradle.kts), so they are deliberately not set here.
 *
 *   ./gradlew build                              build and test everything
 *   ./gradlew testCodeCoverageReport             aggregated coverage
 *   ./gradlew publishAggregationToCentralPortal  release (see RELEASING.md, once written)
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
        ":config-datastore-postgresql",
        ":config-datastore-dynamodb",
        ":config-datastore-etcd",
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

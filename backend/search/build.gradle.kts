plugins {
    id("vaultdoc.conventions")
}

dependencies {
    api(libs.elasticsearch.java)
    api(libs.spring.boot.configuration.processor)
    // ES client 9.x serializes via the Jackson 3 mapper wired in api-gateway.
    api("tools.jackson.core:jackson-databind")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.elasticsearch)
}

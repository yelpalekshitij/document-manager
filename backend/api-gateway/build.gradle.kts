plugins {
    id("vaultdoc.conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    // Every backend module is a library dependency of the gateway.
    implementation(project(":core-auth"))
    implementation(project(":core-crypto"))
    implementation(project(":core-storage"))
    implementation(project(":ingest"))
    implementation(project(":ocr"))
    implementation(project(":extraction"))
    implementation(project(":search"))
    implementation(project(":catalog"))
    implementation(project(":alerts"))
    implementation(project(":sharing"))
    implementation(project(":assistant"))
    implementation(project(":inbox"))
    implementation(project(":household"))
    implementation(project(":billing"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.configuration.processor)

    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgres)
    implementation(libs.postgresql)

    implementation(libs.elasticsearch.java)
    implementation(libs.aws.s3)
    implementation(libs.springdoc.openapi)

    // Spring Boot 4 split the MockMvc test slice into its own starter.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.elasticsearch)
    testImplementation(libs.webauthn4j.test)
    testImplementation(libs.totp)
    testImplementation(libs.awaitility)
    testImplementation(libs.pdfbox)
}

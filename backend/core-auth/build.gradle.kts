plugins {
    id("vaultdoc.conventions")
}

dependencies {
    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.validation)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    implementation(libs.spring.boot.starter.mail)
    api(libs.spring.boot.starter.data.redis)

    api(libs.webauthn4j.core)
    // webauthn4j 0.31 annotates its public API with JetBrains @NotNull/@Nullable
    // but ships them non-transitively; needed at compile time only.
    compileOnly("org.jetbrains:annotations:26.0.2")
    implementation(libs.totp)
    // Jackson 3 (tools.jackson) — webauthn4j 0.31+ and our passkey code reference it
    // directly. Version is governed by the Spring Boot 4.1 BOM.
    api("tools.jackson.core:jackson-databind")

    implementation(libs.argon2)

    testImplementation(libs.spring.boot.starter.test)
}

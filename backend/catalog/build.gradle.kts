plugins {
    id("vaultdoc.conventions")
}

dependencies {
    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.validation)
    api(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
}

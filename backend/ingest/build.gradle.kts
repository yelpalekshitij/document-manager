plugins {
    id("vaultdoc.conventions")
}

dependencies {
    api(project(":catalog"))
    api(project(":core-storage"))
    api(project(":core-pipeline"))
    api(project(":search"))
    api(libs.spring.boot.starter.validation)
    api(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
}

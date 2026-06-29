plugins {
    id("vaultdoc.conventions")
}

dependencies {
    api(project(":catalog"))
    api(project(":core-storage"))
    api(project(":core-pipeline"))
    api(project(":search"))

    api(libs.pdfbox)
    api(libs.spring.boot.configuration.processor)
    api(libs.spring.boot.starter.data.redis)

    testImplementation(libs.spring.boot.starter.test)
}

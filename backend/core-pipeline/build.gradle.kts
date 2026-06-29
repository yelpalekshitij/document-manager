plugins {
    id("vaultdoc.conventions")
}

dependencies {
    api(libs.spring.boot.starter.data.redis)

    testImplementation(libs.spring.boot.starter.test)
}

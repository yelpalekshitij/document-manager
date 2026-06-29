plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Only the dependency-management plugin is needed in the shared conventions.
    // The spring-boot plugin is applied directly by api-gateway via the version
    // catalog — keeping it out of buildSrc avoids a "plugin already on classpath
    // with unknown version" conflict for non-Boot modules.
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}

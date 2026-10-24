// Shared conventions applied by every VaultDoc backend module.
// Each module's build file should be a single line:
//     plugins { id("vaultdoc.conventions") }
// Modules that ARE Spring Boot apps (only api-gateway) add the boot plugin
// in their own build file.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    `java-library`
    jacoco
    id("io.spring.dependency-management")
}

jacoco {
    // 0.8.14 is the first JaCoCo release with official Java 25 class-file support.
    toolVersion = "0.8.14"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}

dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter")
    "implementation"("org.slf4j:slf4j-api")
    // Lombok 1.18.40+ is the first line that runs its processor on JDK 25.
    "compileOnly"("org.projectlombok:lombok:1.18.42")
    "annotationProcessor"("org.projectlombok:lombok:1.18.42")
    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    // Gradle 9 requires the JUnit Platform launcher to be declared explicitly
    // on the test runtime classpath.
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            // JDK 23+ defaults javac to -proc:none; force processors (Lombok) to run.
            "-proc:full",
            "-parameters",
            "-Xlint:all",
            "-Xlint:-processing",
            "-Xlint:-serial",
        )
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
            TestLogEvent.FAILED,
        )
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
    // Virtual threads make sense for JUnit, too — keeps test suites snappy
    // when they exercise blocking I/O against Testcontainers.
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    // Line coverage per module (CLAUDE.md quality bar: ≥80% on core modules).
    // Report lands in build/reports/jacoco/test/ (HTML + XML for CI).
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}

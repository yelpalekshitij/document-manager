// Root project: no source, no plugins applied here.
// Each subproject opts into shared conventions via:
//     plugins { id("vaultdoc.conventions") }
// See buildSrc/src/main/kotlin/vaultdoc.conventions.gradle.kts.

allprojects {
    group = "app.vaultdoc"
    version = "0.1.0-SNAPSHOT"
}

tasks.register("smokeTest") {
    description = "Runs every module's test task — quick CI sanity check."
    group = "verification"
    dependsOn(subprojects.map { "${it.path}:test" })
}

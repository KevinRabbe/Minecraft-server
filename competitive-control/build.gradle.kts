plugins {
    application
    id("com.gradleup.shadow") version "9.6.1"
}

dependencies {
    implementation(project(":common"))

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.github.kevinrabbe.minecraftserver.competitivecontrol.CompetitiveControlMain")
}

// Keep the normal application jar for Gradle distribution tasks and emit a separate self-contained control-plane jar.
tasks.shadowJar {
    archiveClassifier.set("all")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

// Both projects exercise the same disposable PostgreSQL CI database and use TRUNCATE-based integration fixtures.
// Prevent cross-project fixture races while preserving parallelism for non-database work.
tasks.test {
    dependsOn(":common:test")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

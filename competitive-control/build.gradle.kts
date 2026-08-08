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

val verifyShadowRuntimePayload by tasks.registering {
    dependsOn(tasks.shadowJar)

    doLast {
        val shadowJar = tasks.shadowJar.get().archiveFile.get().asFile
        val entries = zipTree(shadowJar)

        val requiredMigrations = listOf(
            "V6__unique_item_authority.sql",
            "V64__reserve_competitive_players_across_categories.sql",
            "V87__fence_complete_competitive_runtime_api.sql",
            "V88__starter_map_issuance_evidence.sql"
        )
        requiredMigrations.forEach { migration ->
            val matches = entries.matching {
                include("db/migration/$migration")
            }.files
            check(matches.isNotEmpty()) {
                "Competitive control shadow JAR is missing db/migration/$migration"
            }
        }
    }
}

// Both projects exercise the same disposable PostgreSQL CI database and use TRUNCATE-based integration fixtures.
// Prevent cross-project fixture races while preserving parallelism for non-database work.
tasks.test {
    dependsOn(":common:test")
}

tasks.build {
    dependsOn(tasks.shadowJar)
    dependsOn(verifyShadowRuntimePayload)
}

plugins {
    id("com.gradleup.shadow") version "9.6.1"
}

dependencies {
    implementation(project(":common"))

    compileOnly("com.velocitypowered:velocity-api:4.1.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:4.1.0-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.shadowJar {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

val verifyShadowRuntimeServices by tasks.registering {
    dependsOn(tasks.shadowJar)

    doLast {
        val shadowJar = tasks.shadowJar.get().archiveFile.get().asFile
        val entries = zipTree(shadowJar)
        val driverClass = entries.matching {
            include("org/postgresql/Driver.class")
        }.files
        check(driverClass.isNotEmpty()) {
            "Velocity shadow JAR is missing org/postgresql/Driver.class"
        }

        val serviceFiles = entries.matching {
            include("META-INF/services/java.sql.Driver")
        }.files
        check(serviceFiles.size == 1) {
            "Velocity shadow JAR must contain exactly one META-INF/services/java.sql.Driver"
        }
        val providers = serviceFiles.single().readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        check("org.postgresql.Driver" in providers) {
            "Velocity shadow JAR JDBC service file does not register org.postgresql.Driver"
        }

        val requiredMigrations = listOf(
            "V6__unique_item_authority.sql",
            "V64__reserve_competitive_players_across_categories.sql",
            "V87__fence_complete_competitive_runtime_api.sql",
            "V88__starter_map_issuance_evidence.sql",
            "V89__allow_rewardless_resource_harvests.sql"
        )
        requiredMigrations.forEach { migration ->
            val matches = entries.matching {
                include("db/migration/$migration")
            }.files
            check(matches.isNotEmpty()) {
                "Velocity shadow JAR is missing db/migration/$migration"
            }
        }
    }
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
    dependsOn(verifyShadowRuntimeServices)
}

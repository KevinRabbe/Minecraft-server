plugins {
    id("com.gradleup.shadow") version "9.6.1"
}

dependencies {
    implementation(project(":common"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testCompileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
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
            "Paper shadow JAR is missing org/postgresql/Driver.class"
        }

        val serviceFiles = entries.matching {
            include("META-INF/services/java.sql.Driver")
        }.files
        check(serviceFiles.size == 1) {
            "Paper shadow JAR must contain exactly one META-INF/services/java.sql.Driver"
        }
        val providers = serviceFiles.single().readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        check("org.postgresql.Driver" in providers) {
            "Paper shadow JAR JDBC service file does not register org.postgresql.Driver"
        }

        val requiredMigrations = listOf(
            "V6__unique_item_authority.sql",
            "V64__reserve_competitive_players_across_categories.sql",
            "V87__fence_complete_competitive_runtime_api.sql"
        )
        requiredMigrations.forEach { migration ->
            val matches = entries.matching {
                include("db/migration/$migration")
            }.files
            check(matches.isNotEmpty()) {
                "Paper shadow JAR is missing db/migration/$migration"
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

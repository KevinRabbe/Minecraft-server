dependencies {
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:12.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:12.10.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    runtimeOnly("org.postgresql:postgresql:42.7.13")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

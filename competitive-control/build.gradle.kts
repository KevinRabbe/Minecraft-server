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

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

plugins {
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    maven {
        name = "spigotmc"
        url = uri("https://hub.spigotmc.org/nexus/content/groups/public/")
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
    implementation("org.postgresql:postgresql:42.7.13")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The legacy runtime is an intentional operational/dependency boundary. It must remain Java-8 bytecode and must not
// depend on :common, whose modern Java domain model is not part of the 1.8.9 runtime trust surface.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
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

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
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

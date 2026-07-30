plugins {
    java
}

allprojects {
    group = "io.github.kevinrabbe.minecraftserver"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven {
            name = "papermc"
            url = uri("https://repo.papermc.io/repository/maven-public/")
        }
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showCauses = true
            showExceptions = true
            showStackTraces = true
        }
    }
}

gradle.projectsEvaluated {
    val commonTest = project(":common").tasks.named<Test>("test")
    val paperTest = project(":paper").tasks.named<Test>("test")
    val competitiveControlTest = project(":competitive-control").tasks.named<Test>("test")

    paperTest.configure {
        mustRunAfter(commonTest)
    }
    competitiveControlTest.configure {
        mustRunAfter(paperTest)
    }
}

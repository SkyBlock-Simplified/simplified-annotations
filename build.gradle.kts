plugins {
    id("java")
    id("maven-publish")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "dev.sbs"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2023.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        bundledPlugin("com.intellij.java")
    }

    // Tests
    testImplementation(group = "org.hamcrest", name = "hamcrest", version = "2.2")
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter-api", version = "5.9.2")
    testRuntimeOnly(group = "org.junit.jupiter", name = "junit-jupiter-engine", version = "5.9.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "232"
        }

        changeNotes = """
      Initial version
    """.trimIndent()
    }
    buildSearchableOptions = false
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Simplified Annotations")
                description.set("This plugin evaluates string expressions marked with the @ResourcePath annotation to check if resource files exist.")
                artifactId = project.name.lowercase()
                version = project.version.toString()
            }
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    register<Copy>("copyPomToResources") {
        dependsOn("generatePomFileForMavenJavaPublication")
        val generatedPom = layout.buildDirectory.file("publications/mavenJava/pom-default.xml")
        from(generatedPom)
        into(layout.buildDirectory.dir("generated-resources"))
        rename { "pom.xml" }
    }
    val sourcesJar by register<Jar>("sourcesJar") {
        dependsOn("copyPomToResources")
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    val javadocJar by register<Jar>("javadocJar") {
        dependsOn("javadoc")
        archiveClassifier.set("javadoc")
        from(javadoc.map { it.destinationDir!! })
    }
    prepareSandbox {
        dependsOn(sourcesJar, javadocJar)
        from(sourcesJar) {
            into("${project.name}/lib")
        }
        from(javadocJar) {
            into("${project.name}/lib")
        }
    }
    processResources {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        dependsOn("copyPomToResources")
    }
}

sourceSets {
    main {
        java.srcDirs("src/main/java")
        resources.srcDirs("src/main/resources", layout.buildDirectory.dir("generated-resources"))
    }
}

import java.security.MessageDigest

plugins {
    id("java")
    id("signing")
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

dependencies {
    // Gradle IntelliJ Plugin (https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
    intellijPlatform {
        create("IC", "2023.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
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
    //buildSearchableOptions = false
}

sourceSets {
    main {
        java.srcDirs("src/main/java")
        resources.srcDirs("src/main/resources")
    }
}

// Checksum Helpers
fun File.generateChecksum(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    digest.update(this.readBytes())
    return digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }
}
fun File.writeChecksumFile(algorithm: String) {
    val checksum = generateChecksum(algorithm)
    val extension = when (algorithm) {
        "SHA-1" -> "sha1"
        "MD5" -> "md5"
        else -> error("Unsupported algorithm: $algorithm")
    }
    val checksumFile = File("${this.absolutePath}.$extension")
    checksumFile.writeText(checksum)
}

// Publishing Directory
val mavenPublishDir = layout.buildDirectory.dir("publications/release")

// Create Pom, Sources and Javadocs
val javadocJar by tasks.registering(Jar::class) {
    archiveBaseName.set(project.name.lowercase())
    archiveClassifier.set("javadoc")
    dependsOn(tasks.javadoc)
    from(tasks.javadoc)
    destinationDirectory.set(mavenPublishDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
val sourcesJar by tasks.registering(Jar::class) {
    archiveBaseName.set(project.name.lowercase())
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
    destinationDirectory.set(mavenPublishDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
val copyMavenMetadata by tasks.registering {
    dependsOn("publishReleasePublicationToMavenLocal")
    notCompatibleWithConfigurationCache("Accesses project files dynamically.")

    doLast {
        val localRepo = File(System.getProperty("user.home"), ".m2/repository")
        val groupPath = project.group.toString().replace('.', '/')
        val artifactId = project.name.lowercase()
        val metadataFile = File(localRepo, "$groupPath/$artifactId/maven-metadata-local.xml")

        if (metadataFile.exists()) {
            val targetDir = mavenPublishDir.get().asFile.resolve("metadata")
            targetDir.mkdirs()
            metadataFile.copyTo(File(targetDir, "maven-metadata.xml"), overwrite = true)
        } else {
            logger.warn("maven-metadata-local.xml not found at: ${metadataFile.absolutePath}")
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

    named<Jar>("jar") {
        archiveBaseName.set(project.name.lowercase())
        destinationDirectory.set(mavenPublishDir)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // Generate checksum and signed files for all files in generated-resources
    val checksumAndSigning by registering {
        dependsOn(buildPlugin, sourcesJar, javadocJar, copyMavenMetadata)
        notCompatibleWithConfigurationCache("Accesses project files dynamically.")

        doLast {
            val outputDir = mavenPublishDir.get().asFile
            outputDir.walkTopDown()
                .filter { it.isFile && !it.extension.matches(Regex("(sha1|md5|asc)")) }
                .forEach { file ->
                    file.writeChecksumFile("SHA-1")
                    file.writeChecksumFile("MD5")
                    exec { commandLine("gpg", "-ab", file.absolutePath) }
                }
        }
    }

    // Zip the files in generated-resources into a single archive
    val mavenZip by registering(Zip::class) {
        dependsOn(checksumAndSigning)
        archiveBaseName.set("${project.name}-${project.version}-maven")
        archiveVersion.set("")
        archiveClassifier.set("")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))

        //val mavenPublicationDir = mavenPublishDir.get().asFile
        val groupPath = project.group.toString().replace('.', '/')
        val artifactId = project.name.lowercase()
        val version = project.version.toString()
        val artifactName = "${artifactId}-${version}"

        from(mavenPublishDir) {
            include("**/*") // Includes JARs, pom.xml, and all checksum files
            rename("pom-default.xml", "${artifactName}.pom")
            rename("${artifactName}-base.jar", "${artifactName}.jar")
            eachFile {
                if (file.isDirectory) {
                    exclude()
                }
            }
            into("$groupPath/$artifactId/$version")
        }

        from(mavenPublishDir.map { it.dir("metadata") }) {
            include("**/*")
            into("$groupPath/$artifactId")
        }
    }

    processResources {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    register("publishAndPackage") {
        dependsOn("publishToMavenLocal", mavenZip)
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifact(tasks.named("jar"))
            artifact(javadocJar)
            artifact(sourcesJar)

            pom {
                name.set("Simplified Annotations")
                description.set("This plugin evaluates string expressions marked with the @ResourcePath annotation to check if resource files exist.")
                artifactId = project.name.lowercase()
                version = project.version.toString()

                developers {
                    developer {
                        name.set("CraftedFury")
                        organization.set("SkyBlock Simplified")
                        organizationUrl.set("https://sbs.dev/")
                    }
                }

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/SkyBlock-Simplified/" + project.name.lowercase() + ".git")
                    developerConnection.set("scm:git:ssh://github.com:SkyBlock-Simplified/" + project.name.lowercase() + ".git")
                    url.set("https://github.com/SkyBlock-Simplified/" + project.name.lowercase())
                }
            }
        }
    }
}

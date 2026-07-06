import org.gradle.api.tasks.Sync

plugins {
    distribution
}

allprojects {
    repositories {
        mavenCentral()
        maven(url = "https://oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
}

subprojects {
    group = property("group") as String
    version = property("version") as String
}

val extensionProjects = listOf(
    ":extensions:cocos:cocos-spi",
    ":extensions:cocos:cocos-cli",
    ":extensions:cocos:cocos-computation-api",
    ":extensions:cocos:cocos-orchestrator",
    ":extensions:cocos:cocos-attestation-credential-service",
    ":extensions:cocos:cocos-data-sink"
)

val packageUpstreamDropins by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Collects the built Cocos EDC extension jars into a drop-in bundle for an upstream EDC deployment."

    dependsOn(extensionProjects.map { "$it:jar" })

    into(layout.buildDirectory.dir("upstream-dropins"))

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from("README.md")
    from("INTEGRATION_PLAN.md")

    into("libs") {
        extensionProjects.forEach { projectPath ->
            from(project(projectPath).layout.buildDirectory.file("libs/${project(projectPath).name}-${project.version}.jar"))
            from(project(projectPath).configurations.getByName("runtimeClasspath")) {
                exclude { details ->
                    val name = details.file.name
                    // Exclude EDC core libraries – always provided by the upstream connector runtime
                    name.contains("jackson-") || name.contains("edc-") ||
                    name.contains("jersey-") || name.contains("jetty-") ||
                    name.startsWith("runtime-metamodel") || name.startsWith("connector-core")
                }
            }
        }
    }
}

distributions {
    main {
        distributionBaseName.set("cocos-edc-extensions")
        contents {
            from(packageUpstreamDropins)
        }
    }
}
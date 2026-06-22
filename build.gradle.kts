plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }

    maven {
        name = "babbaj"
        url = uri("https://babbaj.github.io/maven/")
    }

    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }

    maven {
        name = "modrinth"
        url = uri("https://api.modrinth.com/maven")
    }

    maven {
        name = "lambda"
        url = uri("https://maven.lambda-client.org/releases")
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)

    // Litematica + MaLiLib (Modrinth maven). The Lambda printer reads Litematica's loaded schematic.
    modImplementation("maven.modrinth:malilib:0.27.12")
    modImplementation("maven.modrinth:litematica:0.26.8")

    // Lambda client — compile-only so it isn't bundled; the user installs Lambda as a normal mod.
    // Non-transitive: we only need its Printer class to compile against, not its baritone/jitpack deps.
    modCompileOnly("com.lambda:lambda:0.1.0+1.21.11") { isTransitive = false }

    modImplementation("dev.babbaj:nether-pathfinder:1.6")
    include("dev.babbaj:nether-pathfinder:1.6")


    modImplementation(libs.meteor.client)
    include(libs.meteor.client)

    modImplementation("meteordevelopment:baritone:1.21.11-SNAPSHOT")
    include("meteordevelopment:baritone:1.21.11-SNAPSHOT")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraftVersion.get()
        )

        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}

plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    flatDir {
        dirs("libs")
    }

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
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)

    // Litematica + MaLiLib
    modImplementation(files("libs/litematica-fabric-1.21.4-0.21.4-sakura.4.jar"))
    modImplementation(files("libs/malilib-fabric-1.21.4-0.23.3-sakura.6.jar"))

    // Printer
    modImplementation(files("libs/litematica-printer-1_21.4-3.3.16.jar"))

    modImplementation("dev.babbaj:nether-pathfinder:1.6")
    include("dev.babbaj:nether-pathfinder:1.6")


    modImplementation(libs.meteor.client)
    include(libs.meteor.client)

    modImplementation("meteordevelopment:baritone:1.21.4-SNAPSHOT")
    include("meteordevelopment:baritone:1.21.4-SNAPSHOT")
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

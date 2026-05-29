pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.meteordev.org/releases")
        maven("https://maven.meteordev.org/snapshots")
        maven("https://babbaj.github.io/maven/")
    }
}

rootProject.name = "addon-template"

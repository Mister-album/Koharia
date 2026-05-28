dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../libs.versions.toml"))
        }
        create("kohariax") {
            from(files("../koharia.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

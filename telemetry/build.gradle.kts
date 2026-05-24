import koharia.gradle.Config

plugins {
    alias(kohariax.plugins.android.library)
    alias(kohariax.plugins.spotless)
}

android {
    namespace = "koharia.telemetry"

    sourceSets {
        getByName("main") {
            if (Config.includeTelemetry) {
                kotlin.directories.add("src/firebase/kotlin")
            } else {
                kotlin.directories.add("src/noop/kotlin")
                manifest.srcFile("src/noop/AndroidManifext.xml")
            }
        }
    }
}

dependencies {
    implementation(projects.core.common)

    if (Config.includeTelemetry) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.analytics)
        implementation(libs.firebase.crashlytics)
    }
}

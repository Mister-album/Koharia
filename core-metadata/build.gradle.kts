plugins {
    alias(kohariax.plugins.android.library)
    alias(kohariax.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "tachiyomi.core.metadata"
}

dependencies {
    implementation(projects.sourceApi)

    implementation(libs.bundles.serialization)
}

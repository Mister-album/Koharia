plugins {
    alias(kohariax.plugins.android.library)
    alias(kohariax.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "koharia.core.archive"
}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.archive)
    implementation(libs.unifile)
}

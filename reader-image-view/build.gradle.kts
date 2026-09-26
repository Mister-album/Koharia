plugins {
    alias(kohariax.plugins.android.library)
}

android {
    namespace = "com.davemorrissey.labs.subscaleview"
}

dependencies {
    implementation(libs.androidx.annotation)
    api(libs.image.decoder)
}

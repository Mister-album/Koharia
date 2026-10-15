plugins {
    alias(kohariax.plugins.android.library)
    alias(kohariax.plugins.spotless)
}

android {
    namespace = "tachiyomi.decoder"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                targets += "imagedecoder"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

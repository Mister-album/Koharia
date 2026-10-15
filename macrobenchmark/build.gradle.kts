plugins {
    alias(kohariax.plugins.android.test)
    alias(kohariax.plugins.spotless)
}

val useDeviceTestFixture = providers.gradleProperty("deviceTestFixture")
    .map(String::toBoolean).getOrElse(false)

android {
    namespace = "tachiyomi.macrobenchmark"

    defaultConfig {
        testInstrumentationRunner = "tachiyomi.macrobenchmark.KohariaBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.enabledRules"] = "BaselineProfile"
    }

    buildTypes {
        // This benchmark buildType is used for benchmarking, and should function like your
        // release build (for example, with minification on). It's signed with a debug key
        // for easy local/CI testing.
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks.add("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macroJunit4)
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}

tasks.matching {
    it.name.startsWith("assemble") || it.name.startsWith("install") || it.name.startsWith("connected")
}.configureEach {
    inputs.property("deviceTestFixture", useDeviceTestFixture)
    doFirst {
        check(inputs.properties["deviceTestFixture"] == true) {
            "Benchmarks require -PdeviceTestFixture=true; manually used app variants are forbidden."
        }
    }
}

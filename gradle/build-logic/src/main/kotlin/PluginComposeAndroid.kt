import com.android.build.api.dsl.BuildFeatures
import com.android.build.api.dsl.CommonExtension
import koharia.gradle.extensions.alias
import koharia.gradle.extensions.android
import koharia.gradle.extensions.api
import koharia.gradle.extensions.debugApi
import koharia.gradle.extensions.implementation
import koharia.gradle.extensions.libs
import koharia.gradle.extensions.plugins
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

@Suppress("UNUSED")
class PluginComposeAndroid : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.kotlin.compose.compiler)
        }

        android {
            buildFeatures {
                compose = true
            }
        }

        dependencies {
            implementation(platform(libs.androidx.compose.bom))

            // Compose @Preview tooling
            api(libs.androidx.compose.uiToolingPreview)
            debugApi(libs.androidx.compose.uiTooling)
        }
    }
}

private fun CommonExtension.buildFeatures(block: BuildFeatures.() -> Unit) {
    buildFeatures.apply(block)
}

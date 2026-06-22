import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.CompileOptions
import com.android.build.api.dsl.DefaultConfig
import koharia.gradle.configurations.configureKotlin
import koharia.gradle.extensions.android
import koharia.gradle.extensions.configureTest
import koharia.gradle.extensions.coreLibraryDesugaring
import koharia.gradle.extensions.kohariax
import koharia.gradle.extensions.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

@Suppress("UNUSED")
class PluginAndroidBase : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        configureKotlin()
        configureTest()

        android {
            defaultConfig {
                minSdk = kohariax.versions.android.sdk.min.get().toInt()
                if (this is ApplicationDefaultConfig) {
                    targetSdk = kohariax.versions.android.sdk.target.get().toInt()
                }

                ndkVersion = kohariax.versions.android.ndk.get()
            }

            compileSdk = kohariax.versions.android.sdk.compile.get().toInt()

            compileOptions {
                isCoreLibraryDesugaringEnabled = true
            }
        }

        dependencies {
            coreLibraryDesugaring(libs.android.desugar)
        }
    }
}

private fun CommonExtension.defaultConfig(block: DefaultConfig.() -> Unit) {
    defaultConfig.apply(block)
}

private fun CommonExtension.compileOptions(block: CompileOptions.() -> Unit) {
    compileOptions.apply(block)
}

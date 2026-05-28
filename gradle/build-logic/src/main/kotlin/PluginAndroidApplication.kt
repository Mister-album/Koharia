import koharia.gradle.extensions.alias
import koharia.gradle.extensions.libs
import koharia.gradle.extensions.kohariax
import koharia.gradle.extensions.plugins
import org.gradle.api.Plugin
import org.gradle.api.Project

@Suppress("UNUSED")
class PluginAndroidApplication : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.android.application)
            alias(kohariax.plugins.android.base)
        }
    }
}

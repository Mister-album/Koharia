import koharia.gradle.extensions.alias
import koharia.gradle.extensions.kohariax
import koharia.gradle.extensions.libs
import koharia.gradle.extensions.plugins
import org.gradle.api.Plugin
import org.gradle.api.Project

@Suppress("UNUSED")
class PluginAndroidTest : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.android.test)
            alias(kohariax.plugins.android.base)
        }
    }
}

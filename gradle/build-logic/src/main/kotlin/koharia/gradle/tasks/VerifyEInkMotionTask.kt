package koharia.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyEInkMotionTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectories: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val root = project.rootProject.projectDir
        val violations = mutableListOf<String>()

        sourceDirectories.asFileTree
            .matching { include("**/*.kt") }
            .files
            .sortedBy { it.path }
            .forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                val source = file.readText()
                val imports = source.lineSequence()
                    .map(String::trim)
                    .filter { it.startsWith("import ") }
                    .map { it.removePrefix("import ").substringBefore(" as ") }
                    .toSet()

                FORBIDDEN_IMPORTS.forEach { importName ->
                    if (importName in imports && path !in CENTRAL_MOTION_FILES) {
                        violations += "$path: direct $importName usage must go through the E-Ink motion layer"
                    }
                }

                POLICY_REVIEWED_IMPORTS.forEach { importName ->
                    if (importName in imports &&
                        "LocalEInkDisplayPolicy" !in source &&
                        "EInkPreferences" !in source &&
                        path !in CENTRAL_MOTION_FILES
                    ) {
                        violations += "$path: direct $importName usage requires an explicit E-Ink policy branch"
                    }
                }

                if ("android.animation.ValueAnimator" in imports && path !in REVIEWED_VALUE_ANIMATOR_FILES) {
                    violations += "$path: ValueAnimator usage requires an explicit E-Ink policy review"
                }
                if ("rememberInfiniteTransition(" in source && path !in CENTRAL_MOTION_FILES) {
                    violations += "$path: infinite animation must provide a static E-Ink state"
                }
                if (ANIMATE_AS_STATE.any(source::contains) &&
                    "eInkAnimationSpec" !in source &&
                    path !in CENTRAL_MOTION_FILES
                ) {
                    violations += "$path: animate*AsState must use eInkAnimationSpec"
                }
                if (PROGRAMMATIC_SCROLL.any(source::contains) &&
                    "LocalEInkDisplayPolicy" !in source &&
                    path !in REVIEWED_PROGRAMMATIC_SCROLL_FILES
                ) {
                    violations += "$path: animated programmatic scrolling must have an immediate E-Ink branch"
                }
                if (".animateItem(" in source && "LocalEInkDisplayPolicy" !in source) {
                    violations += "$path: animateItem must have a no-animation E-Ink branch"
                }
                if (".crossfade(true)" in source) {
                    violations += "$path: image crossfade must be disabled by E-Ink policy"
                }
            }

        check(violations.isEmpty()) {
            violations.joinToString(
                prefix = "Unreviewed motion APIs found:\n",
                separator = "\n",
            )
        }
    }

    private companion object {
        val FORBIDDEN_IMPORTS = setOf(
            "androidx.compose.animation.AnimatedContent",
            "androidx.compose.animation.AnimatedVisibility",
            "androidx.compose.animation.animateContentSize",
            "androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter",
            "androidx.compose.material3.CircularProgressIndicator",
            "androidx.compose.material3.LinearProgressIndicator",
        )

        val POLICY_REVIEWED_IMPORTS = setOf(
            "android.view.ViewPropertyAnimator",
            "androidx.compose.animation.core.animate",
            "androidx.compose.material3.pulltorefresh.PullToRefreshDefaults",
        )

        val ANIMATE_AS_STATE = setOf(
            "animateFloatAsState(",
            "animateColorAsState(",
            "animateDpAsState(",
            "animateIntAsState(",
        )

        val PROGRAMMATIC_SCROLL = setOf(
            ".animateScrollToItem(",
            ".animateScrollToPage(",
            ".smoothScrollBy(",
        )

        val CENTRAL_MOTION_FILES = setOf(
            "presentation-core/src/main/java/tachiyomi/presentation/core/motion/EInkMotion.kt",
            "presentation-core/src/main/java/tachiyomi/presentation/core/components/CircularProgressIndicator.kt",
        )

        val REVIEWED_VALUE_ANIMATOR_FILES = setOf(
            "app/src/main/java/koharia/epub/EpubPageTransitionController.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt",
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonRecyclerView.kt",
        )

        val REVIEWED_PROGRAMMATIC_SCROLL_FILES = setOf(
            "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt",
        )
    }
}

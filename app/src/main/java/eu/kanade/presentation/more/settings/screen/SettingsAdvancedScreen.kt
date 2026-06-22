package eu.kanade.presentation.more.settings.screen

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.advanced.ClearDatabaseScreen
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.library.MetadataUpdateJob
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.powerManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsAdvancedScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_advanced

    @Composable
    override fun getPreferences(): List<Preference> {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val basePreferences = remember { Injekt.get<BasePreferences>() }
        val networkPreferences = remember { Injekt.get<NetworkPreferences>() }
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }

        return listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_dump_crash_logs),
                subtitle = stringResource(MR.strings.pref_dump_crash_logs_summary),
                onClick = {
                    scope.launch {
                        CrashLogUtil(context).dumpLogs()
                    }
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_debug_info),
                onClick = { navigator.push(DebugInfoScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_onboarding_guide),
                onClick = { navigator.push(OnboardingScreen()) },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_manage_notifications),
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                },
            ),
            getBackgroundActivityGroup(),
            getDataGroup(),
            getNetworkGroup(networkPreferences = networkPreferences),
            getLibraryGroup(libraryPreferences = libraryPreferences),
            getReaderGroup(basePreferences = basePreferences),
        )
    }

    @Composable
    private fun getBackgroundActivityGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_background_activity),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_disable_battery_optimization),
                    subtitle = stringResource(MR.strings.pref_disable_battery_optimization_summary),
                    onClick = {
                        val packageName: String = context.packageName
                        if (!context.powerManager.isIgnoringBatteryOptimizations(packageName)) {
                            try {
                                @SuppressLint("BatteryLife")
                                val intent = Intent().apply {
                                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                    data = "package:$packageName".toUri()
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                context.toast(MR.strings.battery_optimization_setting_activity_not_found)
                            }
                        } else {
                            context.toast(MR.strings.battery_optimization_disabled)
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = "Don't kill my app!",
                    subtitle = stringResource(MR.strings.about_dont_kill_my_app),
                    onClick = { uriHandler.openUri("https://dontkillmyapp.com/") },
                ),
            ),
        )
    }

    @Composable
    private fun getDataGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_data),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_invalidate_download_cache),
                    subtitle = stringResource(MR.strings.pref_invalidate_download_cache_summary),
                    onClick = {
                        Injekt.get<DownloadCache>().invalidateCache()
                        context.toast(MR.strings.download_cache_invalidated)
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_clear_database),
                    subtitle = stringResource(MR.strings.pref_clear_database_summary),
                    onClick = { navigator.push(ClearDatabaseScreen()) },
                ),
            ),
        )
    }

    @Composable
    private fun getNetworkGroup(
        networkPreferences: NetworkPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_network),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = networkPreferences.verboseLogging,
                    title = stringResource(MR.strings.pref_verbose_logging),
                    subtitle = stringResource(MR.strings.pref_verbose_logging_summary),
                    onValueChanged = {
                        context.toast(MR.strings.requires_app_restart)
                        true
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getLibraryGroup(
        libraryPreferences: LibraryPreferences,
    ): Preference.PreferenceGroup {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.label_library),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_refresh_library_covers),
                    onClick = { MetadataUpdateJob.startNow(context) },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_reset_viewer_flags),
                    subtitle = stringResource(MR.strings.pref_reset_viewer_flags_summary),
                    onClick = {
                        scope.launchNonCancellable {
                            val success = Injekt.get<ResetViewerFlags>().await()
                            withUIContext {
                                val message = if (success) {
                                    MR.strings.pref_reset_viewer_flags_success
                                } else {
                                    MR.strings.pref_reset_viewer_flags_error
                                }
                                context.toast(message)
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.updateMangaTitles,
                    title = stringResource(MR.strings.pref_update_library_manga_titles),
                    subtitle = stringResource(MR.strings.pref_update_library_manga_titles_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = libraryPreferences.disallowNonAsciiFilenames,
                    title = stringResource(MR.strings.pref_disallow_non_ascii_filenames),
                    subtitle = stringResource(MR.strings.pref_disallow_non_ascii_filenames_details),
                ),
            ),
        )
    }

    @Composable
    private fun getReaderGroup(
        basePreferences: BasePreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val chooseColorProfile = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                basePreferences.displayProfile.set(uri.toString())
            }
        }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reader),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = basePreferences.hardwareBitmapThreshold,
                    entries = GLUtil.CUSTOM_TEXTURE_LIMIT_OPTIONS
                        .mapIndexed { index, option ->
                            val display = if (index == 0) {
                                stringResource(MR.strings.pref_hardware_bitmap_threshold_default, option)
                            } else {
                                option.toString()
                            }
                            option to display
                        }
                        .toMap()
                        .toImmutableMap(),
                    title = stringResource(MR.strings.pref_hardware_bitmap_threshold),
                    subtitleProvider = { value, options ->
                        stringResource(MR.strings.pref_hardware_bitmap_threshold_summary, options[value].orEmpty())
                    },
                    enabled = !ImageUtil.HARDWARE_BITMAP_UNSUPPORTED &&
                        GLUtil.DEVICE_TEXTURE_LIMIT > GLUtil.SAFE_TEXTURE_LIMIT,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = basePreferences.alwaysDecodeLongStripWithSSIV,
                    title = stringResource(MR.strings.pref_always_decode_long_strip_with_ssiv_2),
                    subtitle = stringResource(MR.strings.pref_always_decode_long_strip_with_ssiv_summary),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_display_profile),
                    subtitle = basePreferences.displayProfile.get(),
                    onClick = {
                        chooseColorProfile.launch(arrayOf("*/*"))
                    },
                ),
            ),
        )
    }
}

# Baseline profiles

The baseline profile for this app is located at [`app/src/main/baseline-prof.txt`](../app/src/main/baseline-prof.txt).
It contains rules that enable AOT compilation of the critical user path taken during app launch.
For more information on baseline profiles, read [this document](https://developer.android.com/studio/profile/baselineprofiles).

> Note: The baseline profile needs to be re-generated for release builds that touch code which changes app startup.

Follow the isolation and data-retention requirements in [AGENTS.md](../AGENTS.md).
These tests target only `app.koharia.dev.devicefixture` (`Koharia Auto Tests`). The Gradle guard
requires `-PdeviceTestFixture=true`; the runner also verifies the installed target is non-debuggable
and profileable. Android 14 (API 34) or newer is required: the benchmark library can reset ART
profiles without the uninstall/reinstall fallback used on older devices. Never clear app data or
uninstall either APK to retry a run.

Prepare the isolated fixture's onboarding and a local test library before measurement. Use English
or Simplified Chinese for the current navigation journey (library launch → history → more → settings).
The collector also opens generated CBZ, PDF and EPUB files through the exported external-open route,
using a non-exported FileProvider in the separate benchmark APK and explicit read grants. It asserts
decoded page colors or EPUB text, moves forward/backward, and returns to the main navigation.
The comic/PDF assertions account for the fixture's existing single/double-page layout.
Run `tachiyomi.macrobenchmark.ReaderProfileJourneyTest` first against the minified timing target
to verify this journey independently of profile collection. A native local connection must already
exist. Do not use real server accounts in the fixture. Check that no pre-existing incoming-media
sessions are expired: the production external-open route normally cleans sessions older than seven days.
The test does not create accounts, import a backup, or refresh servers. It creates temporary reading
sessions and local progress in the fixture; retain those target-side records as test evidence.
The benchmark's own generated files and read grants are cleaned after the run.
Only isolated content and services belong in this installation; startup may resume existing work.

`tachiyomi.macrobenchmark.ExternalBackupNavigationTest` checks Main/DeepLink backup previews
from the separate runner UID with and without a read grant. It uses invalid, non-sensitive files,
observes whether the provider file is opened, and exits the preview without restoring anything.
It preserves target data and removes only its own files and URI grants.

Build the profile collection target with original symbols:

```powershell
.\gradlew.bat :app:assembleBenchmark :macrobenchmark:assembleBenchmark -PdeviceTestFixture=true -PbaselineProfileFixture=true
```

For timing measurements, omit `-PbaselineProfileFixture=true`. That target inherits Release
minification/resource shrinking, uses the debug signing key, and retains the fixture application ID.
The app instrumentation fixture runner and guards are unchanged.

Inspect the output metadata, actual APK package IDs, signing certificates and version codes before
data-preserving `adb -s <device> install -r` of the app and benchmark APK. Invoke the benchmark APK's
`tachiyomi.macrobenchmark.KohariaBenchmarkRunner` explicitly with `am instrument`; use
`-e class tachiyomi.macrobenchmark.BaselineProfileGenerator` for collection. For timing, select a
startup class/method and add `-e androidx.benchmark.enabledRules Macrobenchmark` (the default is
`BaselineProfile`). Always pass an explicit `additionalTestOutputDir`, pull the generated reports,
profiles and traces into `.test-artifacts/<task>/`, and retain both installed APKs and their data.

The collector runs at most fifteen journeys and requires three stable iterations with strict stability.
A nonempty output from a failed stability check must not be treated as an accepted profile. Startup timing is
collected for all startup modes. Frame timing is collected for cold startup; a resumed surface can
lack the FrameTimeline slices required by that metric, so warm/hot startup timing does not imply
frame coverage.

Review the generated original-symbol profile before updating
[`app/src/main/baseline-prof.txt`](../app/src/main/baseline-prof.txt). A profile from the minified timing
target must not be copied there. Rebuild to check ART profile compilation. Keep the collected
journey, fixture dataset, device, build flags, APK hash and timing results with the run artifacts.
Emulator timings are diagnostic. These representative reader journeys do not establish remote
provider, long-running/large-library or E-Ink performance acceptance.

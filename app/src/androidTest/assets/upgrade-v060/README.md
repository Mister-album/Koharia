# v0.6.0 APK upgrade seed

`LegacyUpgradeSeedTest.kt.txt` is compiled only inside the historical `v0.6.0`
checkout. It requires `app.koharia.dev.einkfixture`, versionCode 12, schema 21,
empty library/inventory and no previous seed. It creates only fake data and no
server addresses, credentials or directory grants. It must not be copied into
the current test source set or used against an existing manual installation.

Use the historical tag's existing `-PeinkDeviceFixture=true` build and unchanged
test runner guards. Install only on the explicitly authorized test device after
checking the actual package, signature and version. Retain packages and data.
After running the seed, stop the fixture and keep a consistent, protected backup
including database WAL/SHM, preferences and files before the current APK update.

Build the current tree with the same fixture flag and install with `adb -s <device>
install -r <verified-apk>` after confirming matching certificates and a non-decreasing
versionCode. Do not uninstall, clear data or downgrade to repeat this journey.

Run `Release060UpgradeDeviceTest#verifyFirstUpgradeKeepsSharedConfiguration` with
instrumentation argument `-e upgrade060 true`, stop the fixture, then invoke
`Release060UpgradeDeviceTest#verifyReopenedAfterProcessRestart` with the same argument.
Without this explicit argument these two phases skip in ordinary test suites.
The verifier does not seed/reset the database and leaves all evidence in the fixture.

This tests the v0.6.0 schema 21 and its four registered providers through an actual
APK replacement and production startup. Historical LANraragi/Smanga pending rows,
common reading data and shared settings must survive. It does not claim real-server
access, SAF permission rebinding, network roots introduced in later versions, or
release-signing continuity from a user-installed production APK.

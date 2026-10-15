# Local-library format verification

`verify-formats.ps1` runs the manually checked format matrix against an explicitly selected device. It accepts only the isolated `app.koharia.dev.devicefixture` package. Build and install that fixture before running the script; it does not install, uninstall, or clear app data.

Prepare a local fixture library, then generate its matrix. The matrix and run evidence are Git-ignored under `.test-artifacts/`.

```powershell
python tools/local-library/generate-fixtures.py --output app/build/local-media-fixtures/standalone-image-regression.epub
python tools/local-library/build-test-cases.py --library 'C:\path\to\fixture-library' --generated-epub app/build/local-media-fixtures/standalone-image-regression.epub --output .test-artifacts/local-library-format-check/test-cases.json
.\tools\local-library\verify-formats.ps1 -Serial 'emulator-5554' -LibraryPath 'C:\path\to\fixture-library' -Mode Inventory
.\tools\local-library\verify-formats.ps1 -Serial 'emulator-5554' -LibraryPath 'C:\path\to\fixture-library' -Mode StepByStep
```

`Inventory` checks hashes and supported extensions. `StepByStep` presents one case at a time and records the operator's result. Resume with the `-ResumeRun` value printed by the script. Run results are written under `.test-artifacts/local-media-runs/`.

## WebDAV conditional mutations

`serve-webdav-preconditions.py` runs an isolated WsgiDAV filesystem and real lock manager on loopback port 18773. It creates its own `dav-root` under the required `.test-artifacts/` output directory and never serves a user library. `strong`, `weak`, and `missing` modes adjust only advertised PROPFIND validators; `bare` retains WsgiDAV's native response. Server request diagnostics record method/status and condition presence, without lock token values.

```powershell
python -m venv .test-artifacts/webdav-preconditions/venv
.\.test-artifacts\webdav-preconditions\venv\Scripts\python.exe -m pip install wsgidav==4.3.3 cheroot==11.0.0
.\.test-artifacts\webdav-preconditions\venv\Scripts\python.exe tools/local-library/serve-webdav-preconditions.py --output .test-artifacts/webdav-preconditions/run
```

In another terminal, build and data-preservingly install the Debug app/test fixture according to `AGENTS.md`, then run the test below against the explicitly selected emulator. Do not overwrite an existing reverse mapping. The test creates unique directories, preserves external-edit payloads until its own cleanup, and removes only its own SQL records. WsgiDAV 4.3.3 can retain source-path locks after native MOVE and throw an internal error while purging them. After deleting its files, the suite reports any empty owned parent whose deletion is blocked by that server lock; remove those parents from the fixture directory after stopping the server. This server limitation is not an application test pass for lease-expiry cleanup.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PdeviceTestFixture=true
$serial = 'YOUR_FIXTURE_EMULATOR'
adb -s $serial reverse --list
adb -s $serial reverse tcp:18773 tcp:18773
adb -s $serial shell am instrument -w -r -e class koharia.storage.WebDavMutationPreconditionDeviceTest app.koharia.dev.devicefixture.test/koharia.testing.KohariaDeviceTestRunner > .test-artifacts/webdav-preconditions/run/device.log 2>&1
adb -s $serial reverse --remove tcp:18773
```

Stop the fixture server after testing. Retain useful logs; inspect and remove only disposable files in that run's `dav-root`. Keep the installed app/test packages and their pre-existing data. These mutation tests must never target real connection data or the manual dev installation.

# LANraragi verification tools

`fixture_server.py` provides generated protocol fixtures for the LANraragi device tests. `verify_sqlite.py` checks the SQLDelight schema and migrations with an in-memory database. Run from the repository root:

```powershell
python tools/lanraragi/verify_sqlite.py
python tools/lanraragi/fixture_server.py --port 0
```

The fixture server prints its selected host port. Map that port to device port `38709` with `adb -s <serial> reverse tcp:38709 tcp:<host-port>`, then run an isolated test class:

```powershell
.\tools\lanraragi\verify-device.ps1 -Serial 'emulator-5554' -TestClass 'koharia.lanraragi.LanraragiConnectionDeviceTest'
```

The device runner uses `-PdeviceTestFixture=true` (`app.koharia.dev.devicefixture`), requires an explicit serial, and retains installed packages and app data. Use `-OfficialDemo` only for a test class that explicitly targets the public demo. Generated evidence belongs under `.test-artifacts/`; Gradle outputs remain in their normal build directories.

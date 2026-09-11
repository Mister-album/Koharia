# E-Ink verification

Run the fast repository checks on Windows:

```powershell
.\tools\eink\verify.ps1
```

Run the isolated motion tests on a connected Android or E-Ink device:

```powershell
.\tools\eink\verify-device.ps1 -Serial <adb-serial>
```

The device test does not change Android animation scales or persisted Koharia preferences. It builds and updates the isolated `app.koharia.dev.einkfixture` target and test packages with `adb install -r`, runs deterministic frame checks, and writes a report under `app/build/reports/eink-device/`. Both packages and their existing data are retained after success or failure. APK package IDs are checked before installation; incompatible signatures cause an error instead of an uninstall/reinstall fallback.

Before release, validate both an E-Ink device and a regular Android device. Check startup, tabs, library, details, settings, dialogs, downloads, comic reading, and EPUB reading. E-Ink mode should settle directly on the final UI state. Reader flashes must follow the configured color, duration, and interval. App-level flashes must remain off by default and must not double-flash inside either reader. Turning E-Ink mode off must restore the saved reader transitions and normal UI motion.

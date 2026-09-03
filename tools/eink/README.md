# E-Ink verification

Run the fast repository checks on Windows:

```powershell
.\tools\eink\verify.ps1
```

Run the isolated motion tests on a connected Android or E-Ink device:

```powershell
.\tools\eink\verify-device.ps1 -Serial <adb-serial>
```

The device test does not change Android animation scales or persisted Koharia preferences. It builds and installs the isolated `app.koharia.dev.einkfixture` target and test packages, runs deterministic frame checks, writes a report under `app/build/reports/eink-device/`, and removes both fixture packages.

Before release, validate both an E-Ink device and a regular Android device. Check startup, tabs, library, details, settings, dialogs, downloads, comic reading, and EPUB reading. E-Ink mode should settle directly on the final UI state. Reader flashes must follow the configured color, duration, and interval. App-level flashes must remain off by default and must not double-flash inside either reader. Turning E-Ink mode off must restore the saved reader transitions and normal UI motion.

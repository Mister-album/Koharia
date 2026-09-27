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

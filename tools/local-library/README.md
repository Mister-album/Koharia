# Local-media format verification

Latest font rendering fix: [TXT / MOBI 字号修复](FONT-SIZE-FIX-20260905.md).

Fixes and targeted regressions: [2026-09-05 修复报告 / fix report](FIX-REPORT-20260905.md).

Latest completed run: [2026-09-05 测试报告 / test report](TEST-REPORT-20260905.md)
— 68 cases reviewed, 53 passed and 15 failed; evidence and limitations are linked in the report.

The test matrix covers every extension declared by `LocalMediaFormats` in both
series and individual-file libraries. It also includes text encodings, animated
images, empty archives, a standalone-image EPUB regression fixture, and large-file
performance probes.

## Prepare and validate fixtures

```powershell
.\tools\local-library\verify-formats.ps1 `
    -Serial emulator-5554 `
    -LibraryPath E:\project\Koharia\local-library-test `
    -Package app.koharia.dev `
    -Mode Inventory
```

Inventory mode verifies all hashes and extension coverage, generates the EPUB
fixture, stages missing series/individual aliases on the device, and writes a
`PREPARED` report. It does not clear application data or the source fixture tree.

## Execute one case step by step

```powershell
.\tools\local-library\verify-formats.ps1 `
    -Serial emulator-5554 `
    -LibraryPath E:\project\Koharia\local-library-test `
    -Package app.koharia.dev `
    -Mode StepByStep
```

`StepByStep` executes exactly one case per invocation. It splits opening, first
frame, page turns, zoom, orientation, resume, animation, and error handling into
separate checkpoints. Perform every action directly in the emulator, observe the
whole transition, and enter `PASS`, `FAIL`, or `BLOCKED` for that checkpoint.
The script only presents instructions and records screenshots, UI hierarchy,
logcat, meminfo, timing, and the operator's notes; it never infers a pass from an
automated assertion.

A failed checkpoint is recorded immediately, followed by one cold-start manual
retry. The first failure remains in the final result even if the retry passes.
After the case, rerun the printed command with `-ResumeRun <run-id>` to execute
the next unfinished case. To select one case explicitly, add `-CaseId <case-id>`.

Build and install the intended APK before beginning the run. The step-by-step
command does not rebuild, reinstall, navigate, turn pages, or decide visual
correctness on the operator's behalf.

Reports are stored under `artifacts/local-media-runs/<run-id>/`. Exit code `0`
means the current case passed, `1` means the current case recorded a product
failure or block, and `2` means the step could not be recorded because of test
infrastructure or missing input. The summary separately reports overall progress.

## Update the checked matrix

Regenerate `test-cases.json` only when fixture content or supported formats change:

```powershell
python tools\local-library\generate-fixtures.py `
    --output app\build\local-media-fixtures\standalone-image-regression.epub
python tools\local-library\build-test-cases.py `
    --library local-library-test `
    --generated-epub app\build\local-media-fixtures\standalone-image-regression.epub `
    --output tools\local-library\test-cases.json
```

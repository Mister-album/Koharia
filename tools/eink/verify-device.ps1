param(
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$targetPackage = "app.koharia.dev.einkfixture"
$testPackage = "$targetPackage.test"
$runner = "androidx.test.runner.AndroidJUnitRunner"
$testClass = "eu.kanade.tachiyomi.ui.eink.EInkMotionDeviceTest"

function Invoke-Checked {
    param([scriptblock]$Command, [string]$Description)
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code $LASTEXITCODE."
    }
}

Push-Location $root
try {
    Invoke-Checked { adb -s $Serial get-state } "ADB device check"

    $reportDir = "app\build\reports\eink-device"
    New-Item -ItemType Directory -Force $reportDir | Out-Null
    $safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
    $report = Join-Path $reportDir "$safeSerial.txt"

    @(
        "serial=$Serial"
        "model=$(adb -s $Serial shell getprop ro.product.model)"
        "sdk=$(adb -s $Serial shell getprop ro.build.version.sdk)"
        "targetPackage=$targetPackage"
        "testPackage=$testPackage"
        "timestamp=$([DateTimeOffset]::Now.ToString('o'))"
    ) | Set-Content $report

    Invoke-Checked {
        .\gradlew.bat -PeinkDeviceFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
    } "Isolated test APK build"

    $appMetadata = Get-Content "app\build\outputs\apk\debug\output-metadata.json" | ConvertFrom-Json
    $appElement = $appMetadata.elements | Where-Object { $_.filters.Count -eq 0 } | Select-Object -First 1
    if ($null -eq $appElement) { throw "Universal debug APK was not found." }
    $appApk = Join-Path "app\build\outputs\apk\debug" $appElement.outputFile

    $testApk = Get-ChildItem "app\build\outputs\apk\androidTest\debug" -Filter "*.apk" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $testApk) { throw "Android test APK was not found." }

    adb -s $Serial uninstall $testPackage 2>$null | Out-Null
    adb -s $Serial uninstall $targetPackage 2>$null | Out-Null
    Invoke-Checked { adb -s $Serial install -t $appApk } "Isolated debug APK install"
    Invoke-Checked { adb -s $Serial install -t $testApk.FullName } "Isolated test APK install"

    $instrumentation = adb -s $Serial shell am instrument -w -r -e class $testClass "$testPackage/$runner" 2>&1
    $instrumentationExitCode = $LASTEXITCODE
    $instrumentation | Tee-Object -FilePath $report -Append
    $instrumentationText = $instrumentation -join "`n"
    $instrumentationFailed = $instrumentationText -match "FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg="
    $instrumentationCompleted = $instrumentationText -match "OK \([0-9]+ tests?\)"
    if ($instrumentationExitCode -ne 0 -or $instrumentationFailed -or !$instrumentationCompleted) {
        throw "Device motion tests failed. See $report."
    }
} catch {
    if ($null -ne $report) {
        "error=$($_.Exception.Message)" | Add-Content $report
    }
    throw
} finally {
    adb -s $Serial uninstall $testPackage 2>$null | Out-Null
    adb -s $Serial uninstall $targetPackage 2>$null | Out-Null
    Pop-Location
}

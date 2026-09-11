param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_.$#,-]+$')]
    [string]$TestClass,

    [switch]$OfficialDemo
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$previousSerial = $env:ANDROID_SERIAL
Push-Location $projectRoot
try {
    & adb -s $Serial get-state
    if ($LASTEXITCODE -ne 0) { throw "The selected device is not available." }
    $env:ANDROID_SERIAL = $Serial
    # Pass one class per invocation: some Android test runners truncate comma-separated class arguments.
    foreach ($selectedClass in ($TestClass -split ',')) {
        $gradleArguments = @(
            "-PdeviceTestFixture=true",
            ":app:connectedDebugAndroidTest",
            "-Pandroid.testInstrumentationRunnerArguments.class=$selectedClass"
        )
        if ($OfficialDemo) {
            $gradleArguments += "-Pandroid.testInstrumentationRunnerArguments.runLanraragiDemo=true"
        }
        & .\gradlew.bat @gradleArguments
        if ($LASTEXITCODE -ne 0) { throw "Isolated device tests failed. Installed packages and data have been retained." }
        $resultFiles = Get-ChildItem -LiteralPath "app/build/outputs/androidTest-results/connected/debug" -Filter "TEST-*.xml" -Recurse
        $testCount = 0
        foreach ($resultFile in $resultFiles) {
            [xml]$resultXml = Get-Content -LiteralPath $resultFile.FullName -Raw
            $testCount += $resultXml.SelectNodes('//testcase').Count
        }
        if ($testCount -eq 0) { throw "No device test cases executed for $selectedClass." }
        $reportDirectory = Join-Path "app/build/lanraragi/device-results" ($selectedClass -replace '[^A-Za-z0-9_.-]', '_')
        New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
        foreach ($resultFile in $resultFiles) {
            Copy-Item -LiteralPath $resultFile.FullName -Destination $reportDirectory
        }
        Write-Output "Verified $testCount device test case(s) for $selectedClass; APKs and app data retained."
    }
} finally {
    $env:ANDROID_SERIAL = $previousSerial
    Pop-Location
}

param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [string]$LibraryPath,

    [string]$Package = "app.koharia.dev",

    [ValidateSet("Inventory", "StepByStep")]
    [string]$Mode = "StepByStep",

    [string]$ResumeRun,

    [string]$CaseId
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$library = (Resolve-Path $LibraryPath).Path
$caseDefinitionPath = Join-Path $PSScriptRoot "test-cases.json"
$generatedFixture = Join-Path $root "app\build\local-media-fixtures\standalone-image-regression.epub"
$deviceRoot = "/sdcard/Download/KohariaLocalLibraryTest"
$resultRoot = Join-Path $root "artifacts\local-media-runs"
$runId = if ($ResumeRun) { $ResumeRun } else { "{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), ($Serial -replace '[^A-Za-z0-9._-]', '_') }
$runDirectory = Join-Path $resultRoot $runId
$evidenceDirectory = Join-Path $runDirectory "evidence"
$failureDirectory = Join-Path $runDirectory "failures"
$environmentPath = Join-Path $runDirectory "environment.txt"
$manifestPath = Join-Path $runDirectory "manifest.json"
$matrixPath = Join-Path $runDirectory "matrix.csv"
$resultsPath = Join-Path $runDirectory "results.jsonl"
$summaryPath = Join-Path $runDirectory "summary.md"
$transcriptPath = Join-Path $runDirectory "commands.log"
$script:activeLogProcess = $null

function Write-CommandRecord {
    param([string]$Command, [int]$ExitCode, [object[]]$Output)
    @(
        "[$([DateTimeOffset]::Now.ToString('o'))] COMMAND: $Command"
        "EXIT: $ExitCode"
        ($Output -join "`n")
        ""
    ) | Add-Content -LiteralPath $transcriptPath -Encoding utf8
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $output = @(& adb -s $Serial @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    Write-CommandRecord -Command ("adb -s $Serial " + ($Arguments -join " ")) -ExitCode $exitCode -Output $output
    if (!$AllowFailure -and $exitCode -ne 0) {
        throw "ADB command failed with exit code $exitCode`: adb -s $Serial $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Command,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $output = @(& $Command 2>&1)
    $exitCode = $LASTEXITCODE
    Write-CommandRecord -Command $Description -ExitCode $exitCode -Output $output
    $output | Write-Host
    if ($exitCode -ne 0) {
        throw "$Description failed with exit code $exitCode."
    }
}

function Get-DevicePath {
    param([object]$Case)
    return "$deviceRoot/$($Case.deviceRelativePath -replace '\\', '/')"
}

function Get-SourcePath {
    param([object]$Case)
    if ($Case.sourceRoot -eq "generated") {
        return $generatedFixture
    }
    return Join-Path $library ($Case.sourceRelativePath -replace '/', '\')
}

function Save-UiEvidence {
    param([string]$Destination, [string]$Prefix)
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $remoteScreenshot = "/sdcard/koharia-$runId-$Prefix.png"
    $remoteUi = "/sdcard/koharia-$runId-$Prefix.xml"
    Invoke-Adb -Arguments @("shell", "screencap -p '$remoteScreenshot'") | Out-Null
    Invoke-Adb -Arguments @("pull", $remoteScreenshot, (Join-Path $Destination "$Prefix.png")) | Out-Null
    Invoke-Adb -Arguments @("shell", "uiautomator dump '$remoteUi'") -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("pull", $remoteUi, (Join-Path $Destination "$Prefix.xml")) -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("shell", "rm -f '$remoteScreenshot' '$remoteUi'") -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("shell", "dumpsys activity activities") -AllowFailure |
        Set-Content -LiteralPath (Join-Path $Destination "$Prefix-activity.txt") -Encoding utf8
    Invoke-Adb -Arguments @("shell", "dumpsys meminfo '$Package'") -AllowFailure |
        Set-Content -LiteralPath (Join-Path $Destination "$Prefix-meminfo.txt") -Encoding utf8
}

function Get-ResumedActivity {
    $activityLines = @(Invoke-Adb -Arguments @("shell", "dumpsys activity activities | grep -m 1 topResumedActivity") -AllowFailure)
    $line = $activityLines -join " "
    if ($line -match '[^\s]+/(?<activity>[^\s}]+)') {
        return $Matches.activity
    }
    return "unknown"
}

function Start-CaseLog {
    param([string]$Destination, [string]$Prefix = "logcat")
    $stdout = Join-Path $Destination "$Prefix.txt"
    $stderr = Join-Path $Destination "$Prefix-error.txt"
    $processIdOutput = Invoke-Adb -Arguments @("shell", "pidof '$Package'") -AllowFailure | Select-Object -First 1
    $processId = if ($null -ne $processIdOutput) { $processIdOutput.ToString().Trim() } else { "" }
    $arguments = @("-s", $Serial, "logcat", "-v", "threadtime")
    if ($processId -match '^\d+$') {
        $arguments += "--pid=$processId"
    }
    $script:activeLogProcess = Start-Process -FilePath "adb" -ArgumentList $arguments -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
}

function Stop-CaseLog {
    if ($null -ne $script:activeLogProcess) {
        if (!$script:activeLogProcess.HasExited) {
            $script:activeLogProcess.Kill()
            $script:activeLogProcess.WaitForExit()
        }
        $script:activeLogProcess.Dispose()
        $script:activeLogProcess = $null
    }
}

function Read-Choice {
    param([string]$Prompt, [string[]]$Allowed)
    while ($true) {
        $answer = (Read-Host $Prompt).Trim().ToUpperInvariant()
        if ($answer -in $Allowed) { return $answer }
        Write-Host "Allowed values: $($Allowed -join ', ')" -ForegroundColor Yellow
    }
}

function Get-CheckpointInstruction {
    param([string]$Name, [object]$Case)
    switch ($Name) {
        "indexedOnce" { return "停留在本地库，确认目标条目只出现一次，格式、标题、封面及所属书架正确。" }
        "firstFrame" { return "点击目标条目并计时，确认在 $($Case.timeoutSeconds) 秒内进入 $($Case.expectedReader)，首屏有实际内容且没有空白、错误占位或永久加载。" }
        "firstFrameFitted" { return "打开目标 EPUB，观察首个单图页，确认图片完整位于页面内且没有裁切或溢出。" }
        "noInitialResizeFlash" { return "退出后重新打开并全程盯住首屏，确认图片不会先以全尺寸出现再缩小。" }
        "forwardBackward" { return "手动向后翻一页，再向前翻回，确认内容和页码都发生正确变化。" }
        "middleAndEnd" { return "使用进度或目录跳到中间和末尾，分别确认内容能够显示且页面可继续操作。" }
        "zoomPan" { return "执行双指缩放、放大后拖动并恢复，确认图像响应、边界和清晰度正常。" }
        "resume" { return "停在非首页位置，退出阅读器后重新打开，确认恢复位置误差不超过一页。" }
        "seriesBoundary" { return "到达当前册/章节边界，验证上一册和下一册关系、方向与提示正确。" }
        "isolatedEntry" { return "到达文件边界，确认不会串入同目录的其他单文件条目。" }
        "orientation" { return "切换横屏再恢复竖屏，确认页面重新布局且没有裁切、错位或进度跳变。" }
        "animationFrames" { return "停留至少三秒，确认 GIF/WebP 内容持续变化且不是静态首帧。" }
        "staticPixelStable" { return "停留至少三秒，确认静态 WebP 没有异常闪动或内容变化。" }
        "textReadable" { return "阅读连续两页正文，确认编码、换行、标点和中英文字符显示正确。" }
        "tocAndProgress" { return "打开目录并选择一个章节，再查看阅读进度，确认目录跳转与进度一致。" }
        "handledError" { return "打开负向样本，确认出现明确错误信息，而不是空白页、卡死或崩溃。" }
        "appResponsive" { return "错误或大文件加载后执行返回、打开菜单和再次进入，确认应用仍可操作。" }
        "imagePreview" { return "点击单图页图片，确认预览层能够打开、缩放并正常关闭。" }
        "openDuration" { return "冷启动后打开目标大文件，记录从点击到首个可读帧出现的实际毫秒数。" }
        "peakMemory" { return "连续翻阅至少五页后停留，脚本将保存 meminfo；同时记录是否发生卡顿、重载或闪退。" }
        default { return "按测试矩阵要求人工检查：$Name。" }
    }
}

function Write-Reports {
    param([object[]]$Cases, [object[]]$Results, [string]$RunStatus)
    $Cases = @($Cases | Where-Object { $null -ne $_ })
    $Results = @($Results | Where-Object { $null -ne $_ })
    $byId = @{}
    foreach ($result in $Results) { $byId[$result.caseId] = $result }
    $matrix = foreach ($case in $Cases) {
        $result = $byId[$case.caseId]
        [pscustomobject]@{
            caseId = $case.caseId
            extension = $case.extension
            mediaKind = $case.mediaKind
            organizationMode = $case.organizationMode
            support = $case.support
            expectedReader = $case.expectedReader
            expectedOutcome = $case.expectedOutcome
            sourceRelativePath = $case.sourceRelativePath
            deviceRelativePath = $case.deviceRelativePath
            sha256 = $case.sha256
            sizeBytes = $case.sizeBytes
            status = if ($null -ne $result) { $result.status } else { "READY" }
            severity = if ($null -ne $result) { $result.severity } else { "" }
            attempts = if ($null -ne $result) { $result.attempts } else { 0 }
            checkpointPassed = if ($null -ne $result -and $null -ne $result.PSObject.Properties["checkpointPassed"]) {
                $result.checkpointPassed
            } else { 0 }
            checkpointFailed = if ($null -ne $result -and $null -ne $result.PSObject.Properties["checkpointFailed"]) {
                $result.checkpointFailed
            } else { 0 }
            checkpointBlocked = if ($null -ne $result -and $null -ne $result.PSObject.Properties["checkpointBlocked"]) {
                $result.checkpointBlocked
            } else { 0 }
            durationMillis = if ($null -ne $result) { $result.durationMillis } else { 0 }
            actual = if ($null -ne $result) { $result.actual } else { "" }
            observedActivity = if ($null -ne $result -and $null -ne $result.PSObject.Properties["observedActivity"]) {
                $result.observedActivity
            } else { "" }
            evidence = if ($null -ne $result) { $result.evidence } else { "" }
        }
    }
    $matrix | Export-Csv -LiteralPath $matrixPath -NoTypeInformation -Encoding utf8

    $passed = @($Results | Where-Object status -eq "PASS").Count
    $failed = @($Results | Where-Object status -eq "FAIL").Count
    $blocked = @($Results | Where-Object status -eq "BLOCKED").Count
    $remaining = $Cases.Count - $Results.Count
    @(
        "# Koharia local-media format test run"
        ""
        "- Run: ``$runId``"
        "- Device: ``$Serial``"
        "- Status: **$RunStatus**"
        "- Cases: $($Cases.Count)"
        "- Passed: $passed"
        "- Failed: $failed"
        "- Blocked: $blocked"
        "- Remaining: $remaining"
        ""
        "## Failures"
        ""
        $(if ($failed -eq 0 -and $blocked -eq 0) { "None." } else {
            @($Results | Where-Object { $_.status -ne "PASS" } | ForEach-Object {
                "- **$($_.severity)** ``$($_.caseId)``: $($_.actual) — ``$($_.evidence)``"
            }) -join "`n"
        })
    ) | Set-Content -LiteralPath $summaryPath -Encoding utf8
}

function Load-ExistingResults {
    if (!(Test-Path -LiteralPath $resultsPath)) { return @() }
    return @(
        Get-Content -LiteralPath $resultsPath |
            Where-Object { $_.Trim() } |
            ForEach-Object { $_ | ConvertFrom-Json }
    )
}

$exitCode = 2
New-Item -ItemType Directory -Force -Path $runDirectory, $evidenceDirectory, $failureDirectory | Out-Null
if (!(Test-Path -LiteralPath $transcriptPath)) { New-Item -ItemType File -Path $transcriptPath | Out-Null }

Push-Location $root
try {
    Invoke-Checked -Description "python tools/local-library/generate-fixtures.py" -Command {
        python "tools\local-library\generate-fixtures.py" --output $generatedFixture
    }
    Invoke-Adb -Arguments @("get-state") | Out-Null
    $caseDocument = Get-Content -LiteralPath $caseDefinitionPath -Raw | ConvertFrom-Json
    $cases = @($caseDocument.cases)
    if ($cases.Count -eq 0) { throw "The test matrix is empty." }
    if (@($cases.caseId | Group-Object | Where-Object Count -gt 1).Count -ne 0) {
        throw "Duplicate caseId values exist in test-cases.json."
    }

    $formatSource = Get-Content -LiteralPath "app\src\main\java\koharia\media\LocalMediaFormats.kt" -Raw
    $declaredExtensions = @(
        [regex]::Matches($formatSource, 'extensions\s*=\s*setOf\((?<body>[^)]*)\)') |
            ForEach-Object { [regex]::Matches($_.Groups['body'].Value, '"(?<extension>[A-Za-z0-9]+)"') } |
            ForEach-Object { $_.Groups['extension'].Value.ToLowerInvariant() } |
            Sort-Object -Unique
    )
    $manifestExtensions = @($caseDocument.supportedExtensions | ForEach-Object { $_.ToLowerInvariant() } | Sort-Object -Unique)
    if (($declaredExtensions -join ',') -ne ($manifestExtensions -join ',')) {
        throw "Supported-extension drift detected. Code=$($declaredExtensions -join ',') Manifest=$($manifestExtensions -join ',')"
    }
    foreach ($extension in $declaredExtensions) {
        foreach ($organization in @("series", "individual")) {
            if (!($cases | Where-Object { $_.extension -eq $extension -and $_.organizationMode -eq $organization })) {
                throw "Missing $organization test case for .$extension."
            }
        }
    }

    $resolvedCases = foreach ($case in $cases) {
        $sourcePath = Get-SourcePath -Case $case
        if (!(Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Missing source for $($case.caseId): $sourcePath"
        }
        $file = Get-Item -LiteralPath $sourcePath
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash
        if ($file.Length -ne [int64]$case.sizeBytes -or $hash -ne $case.sha256) {
            throw "Fixture changed for $($case.caseId). Regenerate test-cases.json intentionally."
        }
        $case
    }
    $caseDocument | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8

    $hostFiles = Get-ChildItem -LiteralPath $library -Recurse -File
    $hostBytes = ($hostFiles | Measure-Object Length -Sum).Sum
    $remoteSizes = @(Invoke-Adb -Arguments @("shell", "find '$deviceRoot' -type f -exec stat -c %s {} \;"))
    $remoteBytes = ($remoteSizes | ForEach-Object { [int64]$_.ToString().Trim() } | Measure-Object -Sum).Sum
    $gitHead = (& git rev-parse HEAD).Trim()
    $gitStatus = @(& git status --short)
    $apk = Get-ChildItem "app\build\outputs\apk\debug" -Filter "*-x86_64.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    @(
        "timestamp=$([DateTimeOffset]::Now.ToString('o'))"
        "runId=$runId"
        "mode=$Mode"
        "serial=$Serial"
        "package=$Package"
        "gitHead=$gitHead"
        "gitStatus=$($gitStatus -join ';')"
        "model=$((Invoke-Adb -Arguments @('shell', 'getprop ro.product.model')) -join '')"
        "androidRelease=$((Invoke-Adb -Arguments @('shell', 'getprop ro.build.version.release')) -join '')"
        "sdk=$((Invoke-Adb -Arguments @('shell', 'getprop ro.build.version.sdk')) -join '')"
        "packageInfo=$((Invoke-Adb -Arguments @('shell', "dumpsys package '$Package' | grep -E 'versionCode=|versionName=' | head -n 2")) -join ';')"
        "display=$((Invoke-Adb -Arguments @('shell', 'wm size')) -join ';')"
        "webView=$((Invoke-Adb -Arguments @('shell', "dumpsys webviewupdate | grep -m 1 'Current WebView package'")) -join ';')"
        "storage=$((Invoke-Adb -Arguments @('shell', 'df -k /sdcard')) -join ';')"
        "hostLibraryFiles=$($hostFiles.Count)"
        "hostLibraryBytes=$hostBytes"
        "remoteLibraryFiles=$($remoteSizes.Count)"
        "remoteLibraryBytes=$remoteBytes"
        "apk=$($apk.FullName)"
        "apkSha256=$(if ($null -ne $apk) { (Get-FileHash -Algorithm SHA256 -LiteralPath $apk.FullName).Hash } else { '' })"
    ) | Set-Content -LiteralPath $environmentPath -Encoding utf8

    $preparedTargets = @{}
    foreach ($case in $resolvedCases) {
        $remotePath = Get-DevicePath -Case $case
        if ($preparedTargets.ContainsKey($remotePath)) { continue }
        $preparedTargets[$remotePath] = $true
        $sourcePath = Get-SourcePath -Case $case
        $sourceLibraryRelative = if ($case.sourceRoot -eq "library") { $case.sourceRelativePath -replace '\\', '/' } else { "" }
        $alreadyAtTarget = $sourceLibraryRelative -eq ($case.deviceRelativePath -replace '\\', '/')
        $remoteSizeOutput = @(Invoke-Adb -Arguments @("shell", "stat -c %s '$remotePath'") -AllowFailure)
        $remoteSize = if ($remoteSizeOutput.Count -gt 0 -and $remoteSizeOutput[0].ToString().Trim() -match '^\d+$') {
            [int64]$remoteSizeOutput[0].ToString().Trim()
        } else { -1L }
        if ($alreadyAtTarget -and $remoteSize -eq [int64]$case.sizeBytes) { continue }
        if ($remoteSize -eq [int64]$case.sizeBytes) { continue }
        $remoteParent = $remotePath.Substring(0, $remotePath.LastIndexOf('/'))
        Invoke-Adb -Arguments @("shell", "mkdir -p '$remoteParent'") | Out-Null
        Invoke-Adb -Arguments @("push", "-a", $sourcePath, $remotePath) | Out-Null
        $verifiedSize = (Invoke-Adb -Arguments @("shell", "stat -c %s '$remotePath'") | Select-Object -First 1).ToString().Trim()
        if ([int64]$verifiedSize -ne [int64]$case.sizeBytes) {
            throw "Device size mismatch after staging $remotePath."
        }
    }

    if ($Mode -eq "Inventory") {
        $results = Load-ExistingResults
        Write-Reports -Cases $resolvedCases -Results $results -RunStatus "PREPARED"
        Write-Host "Inventory complete: $runDirectory"
        $exitCode = 0
        return
    }

    $preferences = @(Invoke-Adb -Arguments @("shell", "run-as '$Package' sh -c 'cat shared_prefs/source_*.xml'") -AllowFailure) -join "`n"
    foreach ($requiredRoot in @("series-library", "single-file-library", "image-folders")) {
        if ($preferences -notmatch [regex]::Escape($requiredRoot)) {
            throw "The configured local-library roots do not include $requiredRoot. Add the five planned roots, refresh, and resume this run."
        }
    }

    $results = @(Load-ExistingResults | Where-Object { $null -ne $_ })
    $completedIds = @($results | ForEach-Object { $_.caseId })

    if ($results.Count -eq 0) {
        Write-Host "请先在应用中手动刷新五个本地库根目录，确认扫描完成后再开始首个用例。" -ForegroundColor Cyan
        [void](Read-Host "刷新完成后按 Enter")
    }

    if ($CaseId) {
        $case = $resolvedCases | Where-Object caseId -eq $CaseId | Select-Object -First 1
        if ($null -eq $case) { throw "Unknown caseId: $CaseId" }
    } else {
        $case = $resolvedCases | Where-Object { $_.caseId -notin $completedIds } | Select-Object -First 1
    }
    if ($null -eq $case) {
        $hasProductFailures = @($results | Where-Object { $_.status -ne "PASS" }).Count -gt 0
        Write-Reports -Cases $resolvedCases -Results $results -RunStatus $(if ($hasProductFailures) { "COMPLETED_WITH_FAILURES" } else { "PASSED" })
        Write-Host "全部用例均已逐项记录。"
        $exitCode = if ($hasProductFailures) { 1 } else { 0 }
        return
    }
    if ($case.caseId -in $completedIds) {
        $existing = $results | Where-Object caseId -eq $case.caseId | Select-Object -First 1
        Write-Host "用例 $($case.caseId) 已记录为 $($existing.status)。"
        $exitCode = if ($existing.status -eq "PASS") { 0 } else { 1 }
        return
    }

    $caseEvidence = Join-Path $evidenceDirectory $case.caseId
    $checkpointPath = Join-Path $caseEvidence "checkpoints.jsonl"
    New-Item -ItemType Directory -Force -Path $caseEvidence | Out-Null
    Invoke-Adb -Arguments @("shell", "am start -W -n '$Package/eu.kanade.tachiyomi.ui.main.MainActivity'") | Out-Null
    $sessionStamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
    Save-UiEvidence -Destination $caseEvidence -Prefix "00-before-$sessionStamp"
    $sessionLogPrefix = "session-$sessionStamp-logcat"
    Start-CaseLog -Destination $caseEvidence -Prefix $sessionLogPrefix
    $startedAt = [DateTimeOffset]::Now
    $checkpointResults = if (Test-Path -LiteralPath $checkpointPath) {
        @(
            Get-Content -LiteralPath $checkpointPath |
                Where-Object { $_.Trim() } |
                ForEach-Object { $_ | ConvertFrom-Json } |
                Group-Object checkpoint |
                ForEach-Object { $_.Group[-1] } |
                Sort-Object sequence
        )
    } else { @() }
    $completedCheckpoints = @($checkpointResults | ForEach-Object { $_.checkpoint })
    $lastObservedActivity = Get-ResumedActivity

    Write-Host ""
    Write-Host "单步用例：$($case.caseId)" -ForegroundColor Cyan
    Write-Host "条目：$($case.entryTitle)；章节：$($case.chapterTitle)；目标页：$($case.pageNumber)"
    Write-Host "预期：$($case.expectedOutcome)，阅读器 $($case.expectedReader)，打开时限 $($case.timeoutSeconds) 秒"
    Write-Host "本次只执行这一项用例；每个检查点都需要人工观察并单独记录。" -ForegroundColor Cyan

    for ($index = 0; $index -lt $case.assertions.Count; $index++) {
        $checkpoint = $case.assertions[$index]
        if ($checkpoint -in $completedCheckpoints) {
            Write-Host "检查点已记录，继续下一项：$checkpoint" -ForegroundColor DarkGray
            continue
        }
        $prefix = "{0:D2}-{1}" -f ($index + 1), ($checkpoint -replace '[^A-Za-z0-9._-]', '_')
        Write-Host ""
        Write-Host "检查点 $($index + 1)/$($case.assertions.Count)：$checkpoint" -ForegroundColor Yellow
        Write-Host (Get-CheckpointInstruction -Name $checkpoint -Case $case)
        [void](Read-Host "请在虚拟机中亲自完成上述动作并观察全过程，完成后按 Enter 采集现场")
        Save-UiEvidence -Destination $caseEvidence -Prefix $prefix
        $lastObservedActivity = Get-ResumedActivity
        if ($checkpoint -in @("animationFrames", "staticPixelStable")) {
            Start-Sleep -Milliseconds 750
            Save-UiEvidence -Destination $caseEvidence -Prefix "$prefix-frame-1"
            Start-Sleep -Milliseconds 750
            Save-UiEvidence -Destination $caseEvidence -Prefix "$prefix-frame-2"
        }

        $firstStatus = Read-Choice -Prompt "人工判断 PASS、FAIL 或 BLOCKED" -Allowed @("PASS", "FAIL", "BLOCKED")
        $firstActual = Read-Host "观察记录（PASS 可留空；FAIL/BLOCKED 请描述现象、位置和触发动作）"
        while ($firstStatus -ne "PASS" -and [string]::IsNullOrWhiteSpace($firstActual)) {
            $firstActual = Read-Host "该结果必须填写实际现象"
        }
        $severity = if ($firstStatus -eq "PASS") { "" } else {
            Read-Choice -Prompt "严重程度 P0、P1、P2 或 P3" -Allowed @("P0", "P1", "P2", "P3")
        }
        $retryStatus = ""
        $retryActual = ""
        $attempts = 1

        if ($firstStatus -eq "FAIL") {
            Save-UiEvidence -Destination $caseEvidence -Prefix "$prefix-first-failure"
            Invoke-Adb -Arguments @("shell", "dumpsys activity exit-info '$Package'") -AllowFailure |
                Set-Content -LiteralPath (Join-Path $caseEvidence "$prefix-exit-info.txt") -Encoding utf8
            Stop-CaseLog
            Invoke-Adb -Arguments @("shell", "am force-stop '$Package'") | Out-Null
            Invoke-Adb -Arguments @("shell", "am start -W -n '$Package/eu.kanade.tachiyomi.ui.main.MainActivity'") | Out-Null
            Start-CaseLog -Destination $caseEvidence -Prefix "$prefix-retry-logcat"
            Write-Host "应用已冷启动。请手动回到同一内容并只重试当前检查点。" -ForegroundColor Yellow
            [void](Read-Host "重试动作完成后按 Enter 采集现场")
            Save-UiEvidence -Destination $caseEvidence -Prefix "$prefix-retry"
            $retryStatus = Read-Choice -Prompt "重试结果 PASS 或 FAIL" -Allowed @("PASS", "FAIL")
            $retryActual = Read-Host "重试观察记录"
            while ([string]::IsNullOrWhiteSpace($retryActual)) {
                $retryActual = Read-Host "请填写重试后的实际现象"
            }
            $attempts = 2
        }

        $checkpointResult = [pscustomobject]@{
            caseId = $case.caseId
            checkpoint = $checkpoint
            sequence = $index + 1
            firstStatus = $firstStatus
            firstActual = $firstActual
            retryStatus = $retryStatus
            retryActual = $retryActual
            severity = $severity
            attempts = $attempts
            observedActivity = $lastObservedActivity
            capturedAt = [DateTimeOffset]::Now.ToString("o")
            evidencePrefix = $prefix
        }
        ($checkpointResult | ConvertTo-Json -Compress) | Add-Content -LiteralPath $checkpointPath -Encoding utf8
        $checkpointResults += $checkpointResult
    }

    Save-UiEvidence -Destination $caseEvidence -Prefix "99-after"
    Stop-CaseLog
    $duration = [DateTimeOffset]::Now - $startedAt
    $failedCheckpoints = @($checkpointResults | Where-Object firstStatus -eq "FAIL")
    $blockedCheckpoints = @($checkpointResults | Where-Object firstStatus -eq "BLOCKED")
    $caseStatus = if ($failedCheckpoints.Count -gt 0) { "FAIL" } elseif ($blockedCheckpoints.Count -gt 0) { "BLOCKED" } else { "PASS" }
    $severityOrder = @{ P0 = 0; P1 = 1; P2 = 2; P3 = 3 }
    $severityCandidates = @($checkpointResults | Where-Object severity | Sort-Object { $severityOrder[$_.severity] })
    $severity = if ($severityCandidates.Count -gt 0) { $severityCandidates[0].severity } else { "" }
    $actual = @($checkpointResults | Where-Object { $_.firstStatus -ne "PASS" } | ForEach-Object {
        "$($_.checkpoint): first=$($_.firstActual); retry=$($_.retryStatus) $($_.retryActual)"
    }) -join " | "
    $result = [pscustomobject]@{
        caseId = $case.caseId
        status = $caseStatus
        severity = $severity
        attempts = ($checkpointResults | Measure-Object -Property attempts -Maximum).Maximum
        checkpointPassed = @($checkpointResults | Where-Object firstStatus -eq "PASS").Count
        checkpointFailed = $failedCheckpoints.Count
        checkpointBlocked = $blockedCheckpoints.Count
        actual = $actual
        observedActivity = $lastObservedActivity
        startedAt = $startedAt.ToString("o")
        durationMillis = [math]::Round($duration.TotalMilliseconds)
        sourceRelativePath = $case.sourceRelativePath
        deviceRelativePath = $case.deviceRelativePath
        sha256 = $case.sha256
        evidence = "evidence/$($case.caseId)"
        checkpoints = $checkpointResults
    }
    ($result | ConvertTo-Json -Compress -Depth 8) | Add-Content -LiteralPath $resultsPath -Encoding utf8
    $results += $result

    if ($caseStatus -ne "PASS") {
        $caseFailure = Join-Path $failureDirectory $case.caseId
        Copy-Item -LiteralPath $caseEvidence -Destination $caseFailure -Recurse -Force
        @(
            "# $($case.caseId)"
            ""
            "- Severity: $severity"
            "- Expected: $($case.expectedOutcome), $($case.expectedReader)"
            "- Actual: $actual"
            "- SHA-256: $($case.sha256)"
            "- Checkpoint records: evidence/$($case.caseId)/checkpoints.jsonl"
        ) | Set-Content -LiteralPath (Join-Path $caseFailure "notes.md") -Encoding utf8
    }

    $remainingCases = @($resolvedCases | Where-Object { $_.caseId -notin $results.caseId })
    if ($remainingCases.Count -eq 0) {
        $hasProductFailures = @($results | Where-Object { $_.status -ne "PASS" }).Count -gt 0
        $runStatus = if ($hasProductFailures) { "COMPLETED_WITH_FAILURES" } else { "PASSED" }
    } else {
        $runStatus = "RUNNING"
    }
    Write-Reports -Cases $resolvedCases -Results $results -RunStatus $runStatus
    Write-Host "本次用例结果：$caseStatus；运行进度：$($results.Count)/$($resolvedCases.Count)"
    if ($remainingCases.Count -gt 0) {
        Write-Host "下一用例：$($remainingCases[0].caseId)"
        Write-Host ".\tools\local-library\verify-formats.ps1 -Serial $Serial -LibraryPath `"$library`" -Package $Package -Mode StepByStep -ResumeRun $runId"
    }
    $exitCode = if ($caseStatus -eq "PASS") { 0 } else { 1 }
} catch {
    Stop-CaseLog
    $errorMessage = $_.Exception.Message
    "error=$errorMessage" | Add-Content -LiteralPath $environmentPath -Encoding utf8
    $loadedCases = if (Test-Path -LiteralPath $caseDefinitionPath) { @((Get-Content $caseDefinitionPath -Raw | ConvertFrom-Json).cases) } else { @() }
    Write-Reports -Cases $loadedCases -Results (Load-ExistingResults) -RunStatus "INCOMPLETE"
    Write-Host "Test infrastructure error: $errorMessage" -ForegroundColor Red
} finally {
    Stop-CaseLog
    Pop-Location
    Write-Host "Run artifacts: $runDirectory"
}

exit $exitCode

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$SourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $env:RUNNER_TEMP) {
    throw 'RUNNER_TEMP is required. Windows startup gates never run against repository or user data paths.'
}

$RunnerTemp = [System.IO.Path]::GetFullPath($env:RUNNER_TEMP).TrimEnd('\')
$RunnerPrefix = $RunnerTemp + [System.IO.Path]::DirectorySeparatorChar
$SessionRoot = [System.IO.Path]::GetFullPath((Join-Path $RunnerTemp (
            'family-finance-windows-gates-' + [guid]::NewGuid().ToString('N'))))
if (-not $SessionRoot.StartsWith($RunnerPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use a gate root outside RUNNER_TEMP: $SessionRoot"
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -cne $Actual) {
        throw "$Message Expected '$Expected'; received '$Actual'."
    }
}

function Assert-UnderSessionRoot([string]$Path) {
    $FullPath = [System.IO.Path]::GetFullPath($Path)
    $SessionPrefix = $SessionRoot.TrimEnd('\') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $FullPath.StartsWith($SessionPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to access a scenario path outside the unique gate root: $FullPath"
    }
}

function New-Scenario([string]$Name) {
    $Destination = Join-Path $SessionRoot $Name
    Assert-UnderSessionRoot $Destination
    $Archive = Join-Path $SessionRoot "$Name.zip"
    Assert-UnderSessionRoot $Archive
    & git -C $SourceRoot archive --format=zip --output=$Archive HEAD
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $Archive -PathType Leaf)) {
        throw "Could not archive the committed source for isolated scenario '$Name'."
    }
    Expand-Archive -LiteralPath $Archive -DestinationPath $Destination
    Remove-Item -LiteralPath $Archive -Force
    return $Destination
}

function Get-FreePort {
    $Listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $Listener.Start()
        return ([System.Net.IPEndPoint]$Listener.LocalEndpoint).Port
    }
    finally {
        $Listener.Stop()
    }
}

function Add-ImmutableGateLogDirectory([object]$Target, [string]$LogDirectory) {
    $ValidatedLogDirectory = [System.IO.Path]::GetFullPath($LogDirectory)
    Assert-UnderSessionRoot $ValidatedLogDirectory
    $Getter = { $ValidatedLogDirectory }.GetNewClosure()
    $Target | Add-Member -MemberType ScriptProperty -Name GateLogDirectory -Value $Getter
    return $Target
}

function Get-ProcessLog([object]$Process) {
    $LogDirectoryProperty = $Process.PSObject.Properties['GateLogDirectory']
    if ($null -eq $LogDirectoryProperty -or
        [string]::IsNullOrWhiteSpace([string]$LogDirectoryProperty.Value)) {
        throw 'Process log metadata is missing its immutable GateLogDirectory.'
    }
    $LogDirectory = [System.IO.Path]::GetFullPath([string]$LogDirectoryProperty.Value)
    Assert-UnderSessionRoot $LogDirectory
    $Logs = @(Get-ChildItem -LiteralPath $LogDirectory -File -ErrorAction SilentlyContinue |
            Sort-Object Name |
            ForEach-Object { "--- $($_.Name) ---`n$((Get-Content -LiteralPath $_.FullName -Raw -ErrorAction SilentlyContinue))" })
    return [string]::Join("`n", $Logs)
}

function Start-Launcher(
    [string]$ScenarioRoot,
    [int]$Port,
    [string]$Label,
    [switch]$Smoke
) {
    Assert-UnderSessionRoot $ScenarioRoot
    $LogDirectory = Join-Path $ScenarioRoot 'windows-gate-logs'
    New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
    $StandardOutput = Join-Path $LogDirectory "$Label.stdout.log"
    $StandardError = Join-Path $LogDirectory "$Label.stderr.log"
    $Launcher = Join-Path $ScenarioRoot 'scripts\start-local.ps1'
    $Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$Launcher`" -NoBrowser -Port $Port -TimeoutSeconds 120"
    if ($Smoke) {
        $Arguments += ' -Smoke'
    }
    $Process = Start-Process -FilePath 'powershell.exe' -ArgumentList $Arguments `
        -WorkingDirectory $ScenarioRoot -RedirectStandardOutput $StandardOutput `
        -RedirectStandardError $StandardError -PassThru
    $Process = Add-ImmutableGateLogDirectory $Process $LogDirectory
    return $Process
}

function Wait-ForExit([System.Diagnostics.Process]$Process, [int]$TimeoutSeconds, [string]$Description) {
    if (-not $Process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-ProcessTree $Process
        throw "$Description did not exit within $TimeoutSeconds seconds.`n$(Get-ProcessLog $Process)"
    }
    $Process.Refresh()
}

function Stop-ProcessTree([System.Diagnostics.Process]$Process) {
    if ($null -eq $Process -or $Process.HasExited) {
        return
    }
    & taskkill.exe /PID $Process.Id /T /F | Out-Null
    $TaskKillExit = $LASTEXITCODE
    $global:LASTEXITCODE = 0
    $Process.WaitForExit(15000) | Out-Null
    $Process.Refresh()
    if ($TaskKillExit -ne 0 -and -not $Process.HasExited) {
        throw "Could not stop launcher process tree PID $($Process.Id)."
    }
}

function Wait-ForReady([int]$Port, [System.Diagnostics.Process]$Process, [int]$TimeoutSeconds) {
    $Deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $Deadline) {
        if ($Process.HasExited) {
            $Process.Refresh()
            throw "Application launcher exited before readiness (code $($Process.ExitCode)).`n$(Get-ProcessLog $Process)"
        }
        try {
            $Response = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/csrf" -TimeoutSec 2
            if ($Response.data.headerName -eq 'X-XSRF-TOKEN' -and $Response.data.token) {
                return
            }
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Application did not become ready on port $Port within $TimeoutSeconds seconds.`n$(Get-ProcessLog $Process)"
}

function Assert-PortReleased([int]$Port) {
    $Deadline = (Get-Date).AddSeconds(15)
    do {
        $Listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -eq $Listener) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $Deadline)
    throw "Port $Port is still listening after the scenario ended."
}

function Assert-NoScenarioProcesses([string]$ScenarioRoot) {
    $Deadline = (Get-Date).AddSeconds(15)
    do {
        $Matches = @(Get-CimInstance Win32_Process | Where-Object {
                $_.CommandLine -and $_.CommandLine.IndexOf(
                    $ScenarioRoot,
                    [System.StringComparison]::OrdinalIgnoreCase) -ge 0
            })
        if ($Matches.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $Deadline)
    $Details = [string]::Join(', ', @($Matches | ForEach-Object { "$($_.ProcessId):$($_.Name)" }))
    throw "Processes still reference scenario root '$ScenarioRoot': $Details"
}

function Invoke-Smoke([string]$ScenarioRoot, [int]$Port, [string]$Label) {
    $Process = Start-Launcher $ScenarioRoot $Port $Label -Smoke
    Wait-ForExit $Process 240 "Smoke '$Label'"
    if ($Process.ExitCode -ne 0) {
        throw "Smoke '$Label' failed with code $($Process.ExitCode).`n$(Get-ProcessLog $Process)"
    }
    Assert-PortReleased $Port
    Assert-NoScenarioProcesses $ScenarioRoot
}

function Test-ExitedProcessLogRetrieval {
    $Scenario = New-Scenario 'process-log-regression'
    $LogDirectory = Join-Path $Scenario 'windows-gate-logs'
    New-Item -ItemType Directory -Path $LogDirectory | Out-Null
    $StandardOutput = Join-Path $LogDirectory 'exited-child.stdout.log'
    $StandardError = Join-Path $LogDirectory 'exited-child.stderr.log'
    $Arguments = '/d /s /c "echo PROCESS_LOG_METADATA_OK"'
    $Process = Start-Process -FilePath $env:ComSpec -ArgumentList $Arguments `
        -WorkingDirectory $Scenario -RedirectStandardOutput $StandardOutput `
        -RedirectStandardError $StandardError -PassThru
    $Process = Add-ImmutableGateLogDirectory $Process $LogDirectory
    Wait-ForExit $Process 30 'Exited-process log regression child'
    Assert-Equal 0 $Process.ExitCode 'Exited-process log regression child failed.'

    $TamperedLogDirectory = Join-Path $Scenario 'tampered-logs'
    $LiveMutationRejected = $false
    try {
        $Process.GateLogDirectory = $TamperedLogDirectory
    }
    catch {
        $LiveMutationRejected = $true
    }
    Assert-True $LiveMutationRejected 'The real process log directory metadata remained writable.'
    Assert-Equal $LogDirectory ([string]$Process.GateLogDirectory) 'The real process log directory metadata changed.'

    # This deliberately carries no StartInfo, proving log lookup depends only
    # on the immutable directory captured when the child was launched.
    $DetachedLogReference = Add-ImmutableGateLogDirectory ([pscustomobject]@{}) $Process.GateLogDirectory
    $DetachedMutationRejected = $false
    try {
        $DetachedLogReference.GateLogDirectory = $TamperedLogDirectory
    }
    catch {
        $DetachedMutationRejected = $true
    }
    Assert-True $DetachedMutationRejected 'The detached log directory metadata remained writable.'
    Assert-Equal $LogDirectory ([string]$DetachedLogReference.GateLogDirectory) 'The detached log directory metadata changed.'
    Assert-True ((Get-ProcessLog $DetachedLogReference) -match 'PROCESS_LOG_METADATA_OK') 'Could not retrieve logs after the child exited without StartInfo metadata.'
    Assert-NoScenarioProcesses $Scenario
}

function Test-FlywayHistoryOutputParser {
    $Scenario = New-Scenario 'flyway-parser-regression'
    . (Join-Path $Scenario 'scripts\startup-output-parsing.ps1')
    $RepresentativeAbsentOutput = @(
        "CASE WHEN EXISTS (...) THEN 'FLYWAY_HISTORY_PRESENT' ELSE 'FLYWAY_HISTORY_ABSENT' END",
        'FLYWAY_HISTORY_ABSENT',
        '(1 row, 3 ms)'
    )
    $Parsed = ConvertFrom-FlywayHistoryShellOutput -Output $RepresentativeAbsentOutput
    Assert-True (-not $Parsed) 'The H2 Shell header produced a false Flyway-history positive.'

    $InvalidOutputs = @(
        [pscustomobject]@{ Lines = @() },
        [pscustomobject]@{ Lines = @('FLYWAY_HISTORY_PRESENT', 'FLYWAY_HISTORY_PRESENT') },
        [pscustomobject]@{ Lines = @('FLYWAY_HISTORY_PRESENT', 'FLYWAY_HISTORY_ABSENT') },
        [pscustomobject]@{ Lines = @('FLYWAY_HISTORY_PRESENT FLYWAY_HISTORY_ABSENT') }
    )
    $RejectedInvalidOutputs = 0
    foreach ($InvalidOutput in $InvalidOutputs) {
        try {
            ConvertFrom-FlywayHistoryShellOutput -Output @($InvalidOutput.Lines) | Out-Null
        }
        catch {
            $RejectedInvalidOutputs++
        }
    }
    Assert-Equal 4 $RejectedInvalidOutputs 'The Flyway-history parser accepted a zero, duplicate, multiple, or ambiguous status.'
}

function Test-StartupSha256Helper {
    $Scenario = New-Scenario 'sha256-regression'
    . (Join-Path $Scenario 'scripts\startup-file-hashing.ps1')
    $Fixture = Join-Path $Scenario 'known-bytes.bin'
    [System.IO.File]::WriteAllBytes($Fixture, [byte[]](0, 1, 2, 3, 255))
    $ActualHash = Get-Sha256Hex -Path $Fixture
    Assert-Equal 'FF5D8507B6A72BEE2DEBCE2C0054798DEACCDC5D8A1B945B6280CE8AA9CBA52E' $ActualHash 'The startup SHA-256 helper returned the wrong digest.'
}

function Get-DirectorySnapshot([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        return 'ABSENT'
    }
    $Resolved = (Resolve-Path -LiteralPath $Path).Path
    $RootTimestamp = (Get-Item -LiteralPath $Resolved).LastWriteTimeUtc.Ticks
    $Items = @(Get-ChildItem -LiteralPath $Resolved -Recurse -Force | Sort-Object FullName | ForEach-Object {
            $Relative = $_.FullName.Substring($Resolved.Length).TrimStart('\')
            if ($_.PSIsContainer) {
                [pscustomobject]@{
                    type = 'directory'
                    path = $Relative
                    timestamp = $_.LastWriteTimeUtc.Ticks
                }
            }
            else {
                [pscustomobject]@{
                    type = 'file'
                    path = $Relative
                    length = $_.Length
                    sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
                    timestamp = $_.LastWriteTimeUtc.Ticks
                }
            }
        })
    return ([pscustomobject]@{
            present = $true
            timestamp = $RootTimestamp
            items = @($Items)
        } | ConvertTo-Json -Compress -Depth 5)
}

function New-StageOneFixture([string]$ScenarioRoot) {
    $DatabaseBase = Join-Path $ScenarioRoot 'data\family-finance'
    Assert-UnderSessionRoot $DatabaseBase
    & java -cp $script:FixtureClasspath com.familyfinance.migration.StageOneDatabaseFixtureCli $DatabaseBase
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create the synthetic Stage 1 fixture in $ScenarioRoot."
    }
    $Primary = "$DatabaseBase.mv.db"
    Assert-True (Test-Path -LiteralPath $Primary -PathType Leaf) 'The Stage 1 fixture did not create its MVStore primary.'
    return $Primary
}

function Get-DatabaseHashes([string]$DataDirectory) {
    $Hashes = @{}
    foreach ($File in @(Get-ChildItem -LiteralPath $DataDirectory -File |
            Where-Object { $_.Name -like 'family-finance.*.db' })) {
        $Hashes[$File.Name] = (Get-FileHash -LiteralPath $File.FullName -Algorithm SHA256).Hash
    }
    return $Hashes
}

function Get-CompletedBackups([string]$BackupRoot) {
    if (-not (Test-Path -LiteralPath $BackupRoot -PathType Container)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $BackupRoot -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'RESTORE.txt') -PathType Leaf })
}

function Get-PartialBackups([string]$BackupRoot) {
    if (-not (Test-Path -LiteralPath $BackupRoot -PathType Container)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $BackupRoot -Directory |
            Where-Object { $_.Name -like '*.partial' })
}

function Verify-DemoLoginAndLedger([int]$Port) {
    $Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $Csrf = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/csrf" -WebSession $Session -TimeoutSec 5
    $Headers = @{}
    $Headers[[string]$Csrf.data.headerName] = [string]$Csrf.data.token
    $Login = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/auth/login" -Method Post `
        -WebSession $Session -Headers $Headers -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ username = 'demo'; password = 'demo1234' } -TimeoutSec 10
    Assert-Equal 'demo@local.family' ([string]$Login.data.email) 'Restored fixture demo login returned the wrong identity.'
    Assert-Equal 'OWNER' ([string]$Login.data.role) 'Restored fixture demo login returned the wrong role.'
    $Ledger = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/transactions" `
        -WebSession $Session -TimeoutSec 10
    Assert-Equal 12 ([int]$Ledger.data.Count) 'Restored Stage 1 fixture did not preserve all ledger rows.'
}

function Test-AbsentProductionPathsSmoke {
    $Scenario = New-Scenario 'smoke-absent'
    $Data = Join-Path $Scenario 'data'
    $Backups = Join-Path $Scenario 'data-backups'
    Assert-True (-not (Test-Path -LiteralPath $Data)) 'Absent-data scenario started with a data directory.'
    Assert-True (-not (Test-Path -LiteralPath $Backups)) 'Absent-data scenario started with a backup directory.'
    $Port = Get-FreePort
    Invoke-Smoke $Scenario $Port 'absent'
    Assert-True (-not (Test-Path -LiteralPath $Data)) 'Smoke created the production data directory.'
    Assert-True (-not (Test-Path -LiteralPath $Backups)) 'Smoke created the production backup directory.'
    $Primaries = @(Get-ChildItem -LiteralPath (Join-Path $Scenario 'target') -File |
            Where-Object { $_.Name -match '^windows-startup-smoke-[0-9a-f]{32}\.mv\.db$' })
    Assert-Equal 1 $Primaries.Count 'Smoke did not create exactly one uniquely named target database.'
}

function Test-SentinelProductionPathsAndCustomPortSmoke {
    $Scenario = New-Scenario 'smoke-sentinels'
    $Data = Join-Path $Scenario 'data'
    $Backups = Join-Path $Scenario 'data-backups'
    New-Item -ItemType Directory -Path $Data, $Backups | Out-Null
    $DataSentinel = Join-Path $Data 'do-not-touch.bin'
    $BackupSentinel = Join-Path $Backups 'do-not-touch.bin'
    [System.IO.File]::WriteAllBytes($DataSentinel, [byte[]](0, 1, 2, 3, 255))
    [System.IO.File]::WriteAllBytes($BackupSentinel, [byte[]](9, 8, 7, 6))
    $FixedTime = [datetime]::SpecifyKind([datetime]'2026-01-02T03:04:05', [System.DateTimeKind]::Utc)
    (Get-Item -LiteralPath $DataSentinel).LastWriteTimeUtc = $FixedTime
    (Get-Item -LiteralPath $BackupSentinel).LastWriteTimeUtc = $FixedTime
    $BeforeData = Get-DirectorySnapshot $Data
    $BeforeBackups = Get-DirectorySnapshot $Backups
    do {
        $Port = Get-FreePort
    } while ($Port -eq 8080)
    Invoke-Smoke $Scenario $Port 'sentinels-custom-port'
    Assert-Equal $BeforeData (Get-DirectorySnapshot $Data) 'Smoke changed data sentinel names, bytes, hashes, timestamps, or presence.'
    Assert-Equal $BeforeBackups (Get-DirectorySnapshot $Backups) 'Smoke changed backup sentinel names, bytes, hashes, timestamps, or presence.'
}

function Test-UnrelatedPortRejection {
    $Scenario = New-Scenario 'unrelated-port'
    $Port = Get-FreePort
    $Listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try {
        $Listener.Start()
        $Process = Start-Launcher $Scenario $Port 'unrelated-port' -Smoke
        Wait-ForExit $Process 60 'Unrelated-port rejection'
        Assert-True ($Process.ExitCode -ne 0) 'The launcher accepted a port owned by an unrelated listener.'
        Assert-True ((Get-ProcessLog $Process) -match "Port $Port is already in use by another program") 'The occupied-port run failed outside the intended rejection branch.'
    }
    finally {
        $Listener.Stop()
    }
    Assert-PortReleased $Port
    Assert-NoScenarioProcesses $Scenario
}

function Test-BackupRestartCollisionAndRestore {
    $Scenario = New-Scenario 'backup-restore'
    $Data = Join-Path $Scenario 'data'
    $BackupRoot = Join-Path $Scenario 'data-backups'
    $Primary = New-StageOneFixture $Scenario
    Set-Content -LiteralPath (Join-Path $Data 'family-finance.trace.db') -Encoding Ascii -Value 'synthetic companion'
    [System.IO.File]::WriteAllBytes((Join-Path $Data 'family-finance.empty.db'), [byte[]]::new(0))
    $BeforeHashes = Get-DatabaseHashes $Data
    Assert-Equal 3 $BeforeHashes.Count 'Synthetic migration scenario did not contain all three database companions.'
    $PrimaryBeforeInspection = (Get-FileHash -LiteralPath $Primary -Algorithm SHA256).Hash

    New-Item -ItemType Directory -Path $BackupRoot | Out-Null
    $CollisionSentinels = @{}
    foreach ($Offset in -30..300) {
        $Candidate = Join-Path $BackupRoot ((Get-Date).AddSeconds($Offset).ToString('yyyyMMdd-HHmmss'))
        New-Item -ItemType Directory -Path $Candidate -ErrorAction SilentlyContinue | Out-Null
        $Sentinel = Join-Path $Candidate 'collision-sentinel.txt'
        if (-not (Test-Path -LiteralPath $Sentinel)) {
            Set-Content -LiteralPath $Sentinel -Encoding Ascii -Value 'must remain unchanged'
        }
        $CollisionSentinels[$Sentinel] = (Get-FileHash -LiteralPath $Sentinel -Algorithm SHA256).Hash
    }

    $Port = Get-FreePort
    $Process = Start-Launcher $Scenario $Port 'first-migration'
    try {
        Wait-ForReady $Port $Process 180
        $Completed = @(Get-CompletedBackups $BackupRoot)
        $PartialsAfterBackup = @(Get-PartialBackups $BackupRoot)
        Assert-Equal 1 $Completed.Count 'Legacy startup did not create exactly one completed backup.'
        Assert-Equal 0 $PartialsAfterBackup.Count 'Successful backup left a partial directory.'
        Assert-True ($Completed[0].Name -match '^\d{8}-\d{6}-\d+$') 'Backup did not choose a collision-safe suffixed destination.'
        $Manifest = Get-Content -LiteralPath (Join-Path $Completed[0].FullName 'RESTORE.txt') -Raw
        foreach ($Name in $BeforeHashes.Keys) {
            $BackupFile = Join-Path $Completed[0].FullName $Name
            Assert-True (Test-Path -LiteralPath $BackupFile -PathType Leaf) "Backup omitted companion $Name."
            $BackupHash = (Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash
            Assert-Equal $BeforeHashes[$Name] $BackupHash "Backup hash mismatch for $Name."
            Assert-True ($Manifest -match [regex]::Escape("$BackupHash  $Name")) "RESTORE.txt omitted the complete hash entry for $Name."
        }
        Assert-Equal $PrimaryBeforeInspection ((Get-FileHash -LiteralPath (Join-Path $Completed[0].FullName 'family-finance.mv.db') -Algorithm SHA256).Hash) 'Read-only preinspection changed the primary before backup.'
        foreach ($Sentinel in $CollisionSentinels.Keys) {
            Assert-Equal $CollisionSentinels[$Sentinel] ((Get-FileHash -LiteralPath $Sentinel -Algorithm SHA256).Hash) 'Collision handling overwrote a pre-existing destination.'
        }
        $VerifiedBackup = $Completed[0].FullName
    }
    finally {
        Stop-ProcessTree $Process
    }
    Assert-PortReleased $Port
    Assert-NoScenarioProcesses $Scenario

    $CompletedAfterMigration = @(Get-CompletedBackups $BackupRoot)
    $CompletedCount = $CompletedAfterMigration.Count
    $RestartPort = Get-FreePort
    $Restart = Start-Launcher $Scenario $RestartPort 'already-migrated-restart'
    try {
        Wait-ForReady $RestartPort $Restart 180
    }
    finally {
        Stop-ProcessTree $Restart
    }
    Assert-True ((Get-ProcessLog $Restart) -match 'already has Flyway history; no migration backup is required') 'Already-migrated restart did not take the explicit backup-skip branch.'
    $CompletedAfterRestart = @(Get-CompletedBackups $BackupRoot)
    $PartialsAfterRestart = @(Get-PartialBackups $BackupRoot)
    Assert-Equal $CompletedCount $CompletedAfterRestart.Count 'Already-migrated restart created another legacy backup.'
    Assert-Equal 0 $PartialsAfterRestart.Count 'Already-migrated restart left a partial backup.'
    Assert-PortReleased $RestartPort
    Assert-NoScenarioProcesses $Scenario

    $MigratedDirectory = Join-Path $Scenario 'migrated-before-restore'
    New-Item -ItemType Directory -Path $MigratedDirectory | Out-Null
    foreach ($File in @(Get-ChildItem -LiteralPath $Data -File | Where-Object { $_.Name -like 'family-finance.*.db' })) {
        Move-Item -LiteralPath $File.FullName -Destination (Join-Path $MigratedDirectory $File.Name)
    }
    foreach ($File in @(Get-ChildItem -LiteralPath $VerifiedBackup -File | Where-Object { $_.Name -like 'family-finance.*.db' })) {
        Copy-Item -LiteralPath $File.FullName -Destination (Join-Path $Data $File.Name)
    }
    $RestoredFiles = @(Get-ChildItem -LiteralPath $Data -File |
            Where-Object { $_.Name -like 'family-finance.*.db' })
    Assert-Equal $BeforeHashes.Count $RestoredFiles.Count 'Restore did not copy every database companion.'
    foreach ($Name in $BeforeHashes.Keys) {
        $RestoredFile = Join-Path $Data $Name
        Assert-True (Test-Path -LiteralPath $RestoredFile -PathType Leaf) "Restore omitted database companion $Name."
        Assert-Equal $BeforeHashes[$Name] ((Get-FileHash -LiteralPath $RestoredFile -Algorithm SHA256).Hash) "Restore changed database companion $Name."
    }

    $RestorePort = Get-FreePort
    $Restored = Start-Launcher $Scenario $RestorePort 'restored-start'
    try {
        Wait-ForReady $RestorePort $Restored 180
        Verify-DemoLoginAndLedger $RestorePort
    }
    finally {
        Stop-ProcessTree $Restored
    }
    Assert-PortReleased $RestorePort
    Assert-NoScenarioProcesses $Scenario
}

function Test-InterruptedCompanionCopy {
    $Scenario = New-Scenario 'interrupted-copy'
    $Data = Join-Path $Scenario 'data'
    New-StageOneFixture $Scenario | Out-Null
    $LockedCompanion = Join-Path $Data 'family-finance.locked.db'
    Set-Content -LiteralPath $LockedCompanion -Encoding Ascii -Value 'synthetic locked companion'
    $Lock = [System.IO.File]::Open($LockedCompanion, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    $Port = Get-FreePort
    $Process = $null
    try {
        $Process = Start-Launcher $Scenario $Port 'locked-companion'
        Wait-ForExit $Process 180 'Locked-companion startup'
        Assert-True ($Process.ExitCode -ne 0) 'Launcher started despite an interrupted companion backup.'
        Assert-True ((Get-ProcessLog $Process) -match 'The migration backup did not finish') 'Locked-companion run failed outside the intended backup interruption branch.'
    }
    finally {
        $Lock.Dispose()
        if ($null -ne $Process -and -not $Process.HasExited) {
            Stop-ProcessTree $Process
        }
    }
    $BackupRoot = Join-Path $Scenario 'data-backups'
    $CompletedAfterInterruption = @(Get-CompletedBackups $BackupRoot)
    $PartialsAfterInterruption = @(Get-PartialBackups $BackupRoot)
    Assert-Equal 0 $CompletedAfterInterruption.Count 'Interrupted backup published a completed destination.'
    Assert-Equal 1 $PartialsAfterInterruption.Count 'Interrupted backup did not retain exactly one partial destination.'
    Assert-PortReleased $Port
    Assert-NoScenarioProcesses $Scenario
}

$Succeeded = $false
try {
    New-Item -ItemType Directory -Path $SessionRoot | Out-Null
    Write-Host "Synthetic Windows gate root: $SessionRoot"

    $FixtureBuildRoot = New-Scenario 'fixture-build'
    $MavenWrapper = Join-Path $FixtureBuildRoot 'mvnw.cmd'
    $TestClasspathFile = Join-Path $FixtureBuildRoot 'target\windows-gate-test-classpath.txt'
    Push-Location $FixtureBuildRoot
    try {
        & $MavenWrapper -q test-compile dependency:build-classpath '-Dmdep.includeScope=test' "-Dmdep.outputFile=$TestClasspathFile"
        $MavenExit = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    if ($MavenExit -ne 0 -or -not (Test-Path -LiteralPath $TestClasspathFile -PathType Leaf)) {
        throw 'Could not compile the test-only Stage 1 fixture generator.'
    }
    $script:FixtureClasspath = [string]::Join([System.IO.Path]::PathSeparator, @(
            (Join-Path $FixtureBuildRoot 'target\test-classes'),
            (Join-Path $FixtureBuildRoot 'target\classes'),
            (Get-Content -LiteralPath $TestClasspathFile -Raw).Trim()
        ))

    Write-Host 'Parser regression: H2 Shell output requires one exact status line.'
    Test-FlywayHistoryOutputParser
    Write-Host 'Hash regression: startup SHA-256 is independent of module autoload.'
    Test-StartupSha256Helper
    Write-Host 'Gate 0/6: Exited-process logs use immutable launch metadata.'
    Test-ExitedProcessLogRetrieval
    Write-Host 'Gate 1/6: Smoke leaves absent production paths absent.'
    Test-AbsentProductionPathsSmoke
    Write-Host 'Gate 2/6: Smoke preserves sentinels and honors a custom port.'
    Test-SentinelProductionPathsAndCustomPortSmoke
    Write-Host 'Gate 3/6: An unrelated listener is rejected.'
    Test-UnrelatedPortRejection
    Write-Host 'Gate 4/6: Legacy backup, collision, restart, restore, login, and ledger checks.'
    Test-BackupRestartCollisionAndRestore
    Write-Host 'Gate 5/6: A locked companion retains only a partial backup and prevents startup.'
    Test-InterruptedCompanionCopy

    $Succeeded = $true
    Write-Host 'All isolated Windows startup gates passed.'
}
finally {
    if ($Succeeded) {
        if (-not $SessionRoot.StartsWith($RunnerPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean a gate root outside RUNNER_TEMP: $SessionRoot"
        }
        Remove-Item -LiteralPath $SessionRoot -Recurse -Force
    }
    else {
        Write-Warning "Synthetic failure evidence was retained under $SessionRoot"
    }
}

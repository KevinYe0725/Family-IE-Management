[CmdletBinding()]
param(
    [switch]$NoBrowser,
    [switch]$Smoke,
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,
    [ValidateRange(10, 300)]
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$MavenWrapper = Join-Path $ProjectRoot 'mvnw.cmd'
$AppUrl = "http://127.0.0.1:$Port"

function Write-Step([string]$Message) {
    Write-Host "[family-finance] $Message" -ForegroundColor Cyan
}

function Get-JavaMajorVersion {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'Java was not found. Install a Java 17 or newer JDK, then reopen the terminal.'
    }

    # Windows PowerShell 5.1 turns native stderr into NativeCommandError when
    # ErrorActionPreference is Stop. java -version writes its normal output to
    # stderr, so merge the streams inside cmd.exe before PowerShell reads it.
    $VersionLine = ((& $env:ComSpec /d /s /c 'java -version 2>&1') |
        Select-Object -First 1).ToString()
    if ($VersionLine -notmatch '"(?<major>\d+)(?:\.(?<minor>\d+))?') {
        throw "Could not read the Java version from: $VersionLine"
    }

    $Major = [int]$Matches.major
    if ($Major -eq 1 -and $Matches.minor) {
        $Major = [int]$Matches.minor
    }
    return $Major
}

function Test-AppReady {
    try {
        $Response = Invoke-WebRequest -UseBasicParsing -Uri "$AppUrl/api/csrf" -TimeoutSec 2
        if ($Response.StatusCode -ne 200) {
            return $false
        }
        $Payload = $Response.Content | ConvertFrom-Json
        return $Payload.data.headerName -eq 'X-XSRF-TOKEN' -and
            -not [string]::IsNullOrWhiteSpace($Payload.data.token)
    }
    catch {
        return $false
    }
}

function Test-PortInUse {
    $Connection = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -First 1
    return $null -ne $Connection
}

function Get-FamilyFinanceDatabaseFiles([string]$DataDirectory) {
    if (-not (Test-Path -LiteralPath $DataDirectory -PathType Container)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $DataDirectory -File |
            Where-Object { $_.Name -like 'family-finance.*.db' })
}

function Get-ProjectH2Jar {
    $ClasspathFile = Join-Path $ProjectRoot 'target\startup-runtime-classpath.txt'
    New-Item -ItemType Directory -Force -Path (Split-Path $ClasspathFile -Parent) | Out-Null
    Write-Step 'Resolving the project runtime H2 version for the migration safety check...'
    & $MavenWrapper -q dependency:build-classpath '-Dmdep.includeScope=runtime' "-Dmdep.outputFile=$ClasspathFile"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $ClasspathFile -PathType Leaf)) {
        throw 'Could not resolve the project runtime H2 dependency. The application was not started and the database was left unchanged.'
    }
    $ExpectedH2Jar = 'h2-2.3.232.jar'
    $H2Jars = @(Get-Content -LiteralPath $ClasspathFile -Raw -ErrorAction Stop |
            ForEach-Object { $_.Split([System.IO.Path]::PathSeparator) } |
            ForEach-Object { $_.Trim() } |
            Where-Object {
                (Split-Path $_ -Leaf) -eq $ExpectedH2Jar -and
                    $_ -match '[\\/]com[\\/]h2database[\\/]h2[\\/]2\.3\.232[\\/]h2-2\.3\.232\.jar$'
            })
    if ($H2Jars.Count -ne 1) {
        throw "Could not resolve exactly one project runtime $ExpectedH2Jar. The application was not started and the database was left unchanged."
    }
    return $H2Jars[0]
}

function Test-FlywayHistoryPresent([string]$DatabaseBasePath) {
    $H2Jar = Get-ProjectH2Jar
    $JdbcPath = $DatabaseBasePath.Replace('\', '/')
    $JdbcUrl = "jdbc:h2:file:$JdbcPath;IFEXISTS=TRUE;ACCESS_MODE_DATA=r"
    $Sql = "select case when exists (select 1 from information_schema.tables where table_schema = 'PUBLIC' and table_name = 'flyway_schema_history') then 'FLYWAY_HISTORY_PRESENT' else 'FLYWAY_HISTORY_ABSENT' end"
    $Command = "`"java`" -cp `"$H2Jar`" org.h2.tools.Shell -url `"$JdbcUrl`" -user sa -password `"`" -sql `"$Sql`" 2>&1"
    $Output = & $env:ComSpec /d /s /c $Command
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not inspect the existing H2 database for Flyway history. The application was not started and the database was left unchanged.'
    }
    $Text = [string]::Join("`n", @($Output))
    if ($Text -match 'FLYWAY_HISTORY_PRESENT') {
        return $true
    }
    if ($Text -match 'FLYWAY_HISTORY_ABSENT') {
        return $false
    }
    throw 'Could not determine whether the existing H2 database has Flyway history. The application was not started and the database was left unchanged.'
}

function New-LegacyDatabaseBackup([System.IO.FileInfo[]]$DatabaseFiles, [string]$BackupRoot) {
    New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null
    $Timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $Attempt = 0
    while ($true) {
        $Suffix = if ($Attempt -eq 0) { '' } else { "-$Attempt" }
        $Destination = Join-Path $BackupRoot "$Timestamp$Suffix"
        $PartialDestination = "$Destination.partial"
        if ((Test-Path -LiteralPath $Destination) -or (Test-Path -LiteralPath $PartialDestination)) {
            $Attempt++
            continue
        }
        try {
            New-Item -ItemType Directory -Path $PartialDestination -ErrorAction Stop | Out-Null
            break
        }
        catch [System.IO.IOException] {
            $Attempt++
        }
    }

    try {
        $Manifest = @(
            'Family Finance pre-migration H2 backup',
            "Created: $((Get-Date).ToString('o'))",
            'Restore only while the application is stopped: copy every database file in this folder back to data/.',
            'SHA256  File'
        )
        foreach ($DatabaseFile in $DatabaseFiles) {
            $Copy = Join-Path $PartialDestination $DatabaseFile.Name
            Copy-Item -LiteralPath $DatabaseFile.FullName -Destination $Copy -ErrorAction Stop
            $SourceHash = (Get-FileHash -LiteralPath $DatabaseFile.FullName -Algorithm SHA256).Hash
            $CopyHash = (Get-FileHash -LiteralPath $Copy -Algorithm SHA256).Hash
            if ($SourceHash -ne $CopyHash) {
                throw "Verification failed for $($DatabaseFile.Name)."
            }
            $Manifest += "$SourceHash  $($DatabaseFile.Name)"
        }
        Set-Content -LiteralPath (Join-Path $PartialDestination 'RESTORE.txt') -Value $Manifest -Encoding UTF8 -ErrorAction Stop
        Move-Item -LiteralPath $PartialDestination -Destination $Destination -ErrorAction Stop
        return $Destination
    }
    catch {
        throw "The migration backup did not finish. The partial copy was kept at $PartialDestination for inspection; the application was not started. $($_.Exception.Message)"
    }
}

function Wait-ForApplication([System.Diagnostics.Process]$Process) {
    $Deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $Deadline) {
        if (Test-AppReady) {
            return
        }
        if ($Process -and $Process.HasExited) {
            throw "Spring Boot stopped before becoming ready (exit code $($Process.ExitCode))."
        }
        Start-Sleep -Seconds 1
    }
    throw "Spring Boot did not become ready within $TimeoutSeconds seconds."
}

function Open-Application {
    if (-not $NoBrowser) {
        Start-Process $AppUrl
    }
}

if (-not (Test-Path $MavenWrapper -PathType Leaf)) {
    throw "Maven Wrapper is missing: $MavenWrapper"
}

$JavaMajor = Get-JavaMajorVersion
if ($JavaMajor -lt 17) {
    throw "Java $JavaMajor is too old. Install Java 17 or newer."
}
Write-Step "Java $JavaMajor detected."

if (Test-AppReady) {
    if ($Smoke) {
        throw "The Family Finance application is already running at $AppUrl. Smoke mode requires a free port."
    }
    Write-Step "The application is already running at $AppUrl"
    Open-Application
    exit 0
}

if (Test-PortInUse) {
    throw "Port $Port is already in use by another program. Stop it or run: start-local.cmd -Port <another-port>"
}

Set-Location $ProjectRoot

if ($Smoke) {
    $SmokeDirectory = Join-Path $ProjectRoot 'target'
    New-Item -ItemType Directory -Force -Path $SmokeDirectory | Out-Null
    $SmokeDatabase = Join-Path $SmokeDirectory ("windows-startup-smoke-" + [guid]::NewGuid().ToString('N'))
    $SmokeJdbcUrl = "jdbc:h2:file:$($SmokeDatabase.Replace('\', '/'));DB_CLOSE_ON_EXIT=FALSE"
    Write-Step "Starting the Windows smoke instance on port $Port..."
    $Command = "`"$MavenWrapper`" -q spring-boot:run `"-Dspring-boot.run.arguments=--server.port=$Port --spring.datasource.url=$SmokeJdbcUrl`""
    $Process = Start-Process -FilePath $env:ComSpec -ArgumentList '/d', '/s', '/c', $Command `
        -WorkingDirectory $ProjectRoot -PassThru -NoNewWindow
    try {
        Wait-ForApplication $Process
        Write-Step "Smoke check passed: $AppUrl"
        Open-Application
    }
    finally {
        if ($Process -and -not $Process.HasExited) {
            & $env:ComSpec /d /s /c "taskkill /PID $($Process.Id) /T /F >nul 2>&1"
            $TaskKillExit = $LASTEXITCODE
            $Process.Refresh()
            if ($TaskKillExit -ne 0 -and -not $Process.HasExited) {
                throw "Could not stop the smoke process tree (PID $($Process.Id))."
            }
        }
    }
    exit 0
}

$DataDirectory = Join-Path $ProjectRoot 'data'
New-Item -ItemType Directory -Force -Path $DataDirectory | Out-Null
$DatabaseFiles = Get-FamilyFinanceDatabaseFiles $DataDirectory
$PrimaryDatabase = @($DatabaseFiles | Where-Object {
        $_.Name -eq 'family-finance.mv.db' -and $_.Length -gt 0
    } | Select-Object -First 1)
if ($PrimaryDatabase.Count -eq 1) {
    $DatabaseBasePath = Join-Path $DataDirectory 'family-finance'
    if (-not (Test-FlywayHistoryPresent $DatabaseBasePath)) {
        $BackupPath = New-LegacyDatabaseBackup $DatabaseFiles (Join-Path $ProjectRoot 'data-backups')
        Write-Step "Created a verified pre-migration backup at $BackupPath"
    }
    else {
        Write-Step 'Existing database already has Flyway history; no migration backup is required.'
    }
}

Write-Step "Starting Spring Boot at $AppUrl"
Write-Step 'Press Ctrl+C in this terminal to stop the application.'

$BrowserJob = Start-Job -ScriptBlock {
    param($Url, $Timeout, $ShouldOpen)
    $Deadline = (Get-Date).AddSeconds($Timeout)
    while ((Get-Date) -lt $Deadline) {
        try {
            $Response = Invoke-WebRequest -UseBasicParsing -Uri "$Url/" -TimeoutSec 2
            if ($Response.StatusCode -eq 200) {
                if ($ShouldOpen) {
                    Start-Process $Url
                }
                return
            }
        }
        catch {
            Start-Sleep -Seconds 1
        }
    }
} -ArgumentList $AppUrl, $TimeoutSeconds, (-not $NoBrowser)

try {
    & $MavenWrapper spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port"
    exit $LASTEXITCODE
}
finally {
    Stop-Job $BrowserJob -ErrorAction SilentlyContinue
    Remove-Job $BrowserJob -Force -ErrorAction SilentlyContinue
}

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

    $VersionLine = ((& java -version 2>&1) | Select-Object -First 1).ToString()
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
        $Response = Invoke-WebRequest -UseBasicParsing -Uri "$AppUrl/" -TimeoutSec 2
        return $Response.StatusCode -eq 200
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
    Write-Step "The application is already running at $AppUrl"
    Open-Application
    exit 0
}

if (Test-PortInUse) {
    throw "Port $Port is already in use by another program. Stop it or run: start-local.cmd -Port <another-port>"
}

New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot 'data') | Out-Null
Set-Location $ProjectRoot

if ($Smoke) {
    Write-Step "Starting the Windows smoke instance on port $Port..."
    $Command = "`"$MavenWrapper`" -q spring-boot:run -Dspring-boot.run.arguments=--server.port=$Port"
    $Process = Start-Process -FilePath $env:ComSpec -ArgumentList '/d', '/s', '/c', $Command `
        -WorkingDirectory $ProjectRoot -PassThru -NoNewWindow
    try {
        Wait-ForApplication $Process
        Write-Step "Smoke check passed: $AppUrl"
        Open-Application
    }
    finally {
        if ($Process -and -not $Process.HasExited) {
            & taskkill.exe /PID $Process.Id /T /F | Out-Null
        }
    }
    exit 0
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


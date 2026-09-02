[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

if (-not $env:RUNNER_TEMP) {
    throw 'RUNNER_TEMP is required so the quote probe cannot write outside its isolated CI root.'
}

$RunnerTemp = [System.IO.Path]::GetFullPath($env:RUNNER_TEMP).TrimEnd('\')
$ProbeRoot = Join-Path $RunnerTemp ("family-finance quote probe " + [guid]::NewGuid().ToString('N'))
$ProbeRoot = [System.IO.Path]::GetFullPath($ProbeRoot)
$RunnerPrefix = $RunnerTemp + [System.IO.Path]::DirectorySeparatorChar
if (-not $ProbeRoot.StartsWith($RunnerPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use quote-probe root outside RUNNER_TEMP: $ProbeRoot"
}

try {
    New-Item -ItemType Directory -Path $ProbeRoot | Out-Null
    $ProbeBatch = Join-Path $ProbeRoot 'capture argument.cmd'
    $OutputFile = Join-Path $ProbeRoot 'received.txt'
    Set-Content -LiteralPath $ProbeBatch -Encoding Ascii -Value @(
        '@echo off',
        'setlocal',
        '> "%~dp0received.txt" echo(%~1',
        'if not "%~2"=="" exit /b 9'
    )

    $Expected = '-Dprobe=alpha beta;gamma'
    $Command = "`"$ProbeBatch`" `"$Expected`""
    # Start-Process flattens ArgumentList. Supplying one complete argument line
    # preserves this explicit /c envelope and the two quote pairs inside it.
    $CmdArgumentLine = "/d /s /c `"$Command`""
    $Process = Start-Process -FilePath $env:ComSpec -ArgumentList $CmdArgumentLine `
        -WorkingDirectory $ProbeRoot -PassThru -Wait -NoNewWindow

    if ($Process.ExitCode -ne 0) {
        throw "cmd.exe quote probe exited with code $($Process.ExitCode)."
    }
    if (-not (Test-Path -LiteralPath $OutputFile -PathType Leaf)) {
        throw 'cmd.exe quote probe did not execute the batch file under a path containing spaces.'
    }
    $Actual = (Get-Content -LiteralPath $OutputFile -Raw).TrimEnd("`r", "`n")
    if ($Actual -cne $Expected) {
        throw "cmd.exe quote probe did not preserve one quoted property argument. Expected '$Expected'; received '$Actual'."
    }

    Write-Host 'Windows cmd.exe quote-envelope regression passed.'
}
finally {
    if (Test-Path -LiteralPath $ProbeRoot) {
        if (-not $ProbeRoot.StartsWith($RunnerPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean quote-probe root outside RUNNER_TEMP: $ProbeRoot"
        }
        Remove-Item -LiteralPath $ProbeRoot -Recurse -Force
    }
}

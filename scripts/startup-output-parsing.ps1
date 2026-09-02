function ConvertFrom-FlywayHistoryPresenceShellOutput {
    [CmdletBinding()]
    param(
        [AllowEmptyCollection()]
        [object[]]$Output
    )

    $StatusLines = @($Output |
            ForEach-Object { ([string]$_).Trim() } |
            Where-Object { $_ -ceq 'NO_HISTORY' -or $_ -ceq 'HISTORY_PRESENT' })
    if ($StatusLines.Count -ne 1) {
        throw 'H2 inspection must return exactly one Flyway-history presence line.'
    }
    return [string]$StatusLines[0]
}

function ConvertFrom-FlywayMigrationStateShellOutput {
    [CmdletBinding()]
    param(
        [AllowEmptyCollection()]
        [object[]]$Output
    )

    $AllowedStates = @('NO_HISTORY', 'BEHIND_CURRENT', 'CURRENT', 'FAILED')
    $StatusLines = @($Output |
            ForEach-Object { ([string]$_).Trim() } |
            Where-Object { $AllowedStates -ccontains $_ })
    if ($StatusLines.Count -ne 1) {
        throw 'H2 inspection did not return exactly one supported Flyway migration state.'
    }
    return [string]$StatusLines[0]
}

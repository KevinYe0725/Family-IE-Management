function ConvertFrom-FlywayHistoryShellOutput {
    [CmdletBinding()]
    param(
        [AllowEmptyCollection()]
        [object[]]$Output
    )

    $StatusLines = @($Output |
            ForEach-Object { ([string]$_).Trim() } |
            Where-Object {
                $_ -ceq 'FLYWAY_HISTORY_PRESENT' -or
                    $_ -ceq 'FLYWAY_HISTORY_ABSENT'
            })
    if ($StatusLines.Count -ne 1) {
        throw 'H2 inspection must return exactly one unambiguous Flyway-history status line.'
    }
    return ($StatusLines[0] -ceq 'FLYWAY_HISTORY_PRESENT')
}

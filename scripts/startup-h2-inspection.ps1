function Invoke-H2Inspection(
    [string]$H2Jar,
    [string]$InspectorSource,
    [string]$JdbcUrl,
    [long]$LatestMigrationVersion
) {
    $PreviousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 can promote native stderr to NativeCommandError
        # when Stop is active. Capture both streams and the native code first.
        $ErrorActionPreference = 'Continue'
        $Output = @(& java -cp $H2Jar $InspectorSource $JdbcUrl $LatestMigrationVersion 2>&1)
        $NativeExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $PreviousErrorActionPreference
    }
    if ($NativeExitCode -ne 0) {
        throw 'Could not inspect the existing H2 database migration state. The application was not started and the database was left unchanged.'
    }
    return @($Output)
}

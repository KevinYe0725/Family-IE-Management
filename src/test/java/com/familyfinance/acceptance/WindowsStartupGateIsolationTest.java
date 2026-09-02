package com.familyfinance.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WindowsStartupGateIsolationTest {

    @Test
    void sourceCheckoutIsReferencedOnlyForGitArchiveReads() throws Exception {
        List<String> sourceCheckoutReferences = Files.readAllLines(
                        Path.of("scripts", "windows-startup-gates.ps1"))
                .stream()
                .map(String::trim)
                .filter(line -> line.contains("$SourceRoot"))
                .toList();

        assertThat(sourceCheckoutReferences).containsExactly(
                "$SourceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path",
                "& git -C $SourceRoot archive --format=zip --output=$Archive HEAD");
    }

    @Test
    void exitedProcessLogLookupUsesGetterOnlyMetadataAndRejectsMutation() throws Exception {
        Path gatePath = Path.of("scripts", "windows-startup-gates.ps1");
        String gate = Files.readString(gatePath);
        List<String> executableLines = Files.readAllLines(gatePath).stream()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .toList();

        assertThat(Pattern.compile("(?i)\\.\\s*StartInfo\\b").matcher(gate).find()).isFalse();
        assertThat(executableLines).contains(
                "function Add-ImmutableGateLogDirectory([object]$Target, [string]$LogDirectory) {",
                "$Getter = { $ValidatedLogDirectory }.GetNewClosure()",
                "$Target | Add-Member -MemberType ScriptProperty -Name GateLogDirectory -Value $Getter",
                "$DetachedLogReference = Add-ImmutableGateLogDirectory ([pscustomobject]@{}) $Process.GateLogDirectory",
                "$Process.GateLogDirectory = $TamperedLogDirectory",
                "$DetachedLogReference.GateLogDirectory = $TamperedLogDirectory",
                "Assert-True $LiveMutationRejected 'The real process log directory metadata remained writable.'",
                "Assert-True $DetachedMutationRejected 'The detached log directory metadata remained writable.'");
        assertThat(executableLines.stream()
                        .filter(line -> line.equals(
                                "$Process = Add-ImmutableGateLogDirectory $Process $LogDirectory")))
                .hasSize(2);
        assertThat(executableLines.stream()
                        .filter(line -> line.contains("-MemberType NoteProperty")
                                && line.contains("GateLogDirectory")))
                .isEmpty();
    }

    @Test
    void flywayHistoryParserIgnoresShellHeadersAndRequiresOneExactStatus() throws Exception {
        Path parserPath = Path.of("scripts", "startup-output-parsing.ps1");
        List<String> parserLines = Files.exists(parserPath) ? executableLines(parserPath) : List.of();
        List<String> startupLines = executableLines(Path.of("scripts", "start-local.ps1"));
        List<String> gateLines = executableLines(Path.of("scripts", "windows-startup-gates.ps1"));

        List<String> representativeAbsentOutput = List.of(
                "CASE WHEN EXISTS (...) THEN 'HISTORY_PRESENT' ELSE 'NO_HISTORY' END",
                "NO_HISTORY",
                "(1 row, 3 ms)");
        Set<String> allowed = Set.of("HISTORY_PRESENT", "NO_HISTORY");
        assertThat(representativeAbsentOutput.stream()
                        .map(String::trim)
                        .filter(allowed::contains))
                .containsExactly("NO_HISTORY");

        assertThat(parserLines).contains(
                "function ConvertFrom-FlywayHistoryPresenceShellOutput {",
                "Where-Object { $_ -ceq 'NO_HISTORY' -or $_ -ceq 'HISTORY_PRESENT' })",
                "function ConvertFrom-FlywayMigrationStateShellOutput {",
                "$AllowedStates = @('NO_HISTORY', 'BEHIND_CURRENT', 'CURRENT', 'FAILED')",
                "$StatusLines = @($Output |",
                "if ($StatusLines.Count -ne 1) {",
                "return [string]$StatusLines[0]");
        assertThat(parserLines.stream()
                        .filter(line -> line.contains("-match") && line.contains("CURRENT")))
                .isEmpty();
        assertThat(startupLines).contains(
                ". (Join-Path $PSScriptRoot 'startup-output-parsing.ps1')",
                "function Get-LatestMigrationVersion {",
                "function Get-FlywayMigrationState([string]$DatabaseBasePath, [long]$LatestMigrationVersion) {",
                "$MigrationState = Get-FlywayMigrationState $DatabaseBasePath $LatestMigrationVersion");
        assertThat(gateLines).contains(
                ". (Join-Path $Scenario 'scripts\\startup-output-parsing.ps1')",
                "$Presence = ConvertFrom-FlywayHistoryPresenceShellOutput -Output $RepresentativeAbsentOutput",
                "$State = ConvertFrom-FlywayMigrationStateShellOutput -Output @(",
                "Assert-Equal 5 $RejectedInvalidOutputs 'The migration-state parser accepted a zero, duplicate, multiple, future, or ambiguous state.'");
    }

    @Test
    void productionPreflightDerivesLatestMigrationAndClassifiesExactHistoryState() throws Exception {
        String parser = Files.readString(Path.of("scripts", "startup-output-parsing.ps1"));
        String startup = Files.readString(Path.of("scripts", "start-local.ps1"));

        assertThat(parser).contains(
                "function ConvertFrom-FlywayMigrationStateShellOutput",
                "'NO_HISTORY'",
                "'BEHIND_CURRENT'",
                "'CURRENT'",
                "'FAILED'");
        assertThat(startup).contains(
                "function Get-LatestMigrationVersion",
                "function Get-FlywayMigrationState",
                "V*.sql",
                "behind repository migration V",
                "repair the invalid data, then run Flyway repair",
                "current at repository migration V");
        assertThat(startup).doesNotContain("function Test-FlywayHistoryPresent");
        assertThat(startup).doesNotContain("flyway:repair", ".repair()");
    }

    @Test
    void h2InspectionPassesOnlyInspectorPathJdbcUrlAndLatestVersionToNativeJava() throws Exception {
        String function = Files.readString(Path.of("scripts", "startup-h2-inspection.ps1"));
        String inspector = Files.readString(Path.of("scripts", "FlywayStateInspector.java"));

        assertThat(function).contains(
                "$PreviousErrorActionPreference = $ErrorActionPreference",
                "$ErrorActionPreference = 'Continue'",
                "$Output = @(& java -cp $H2Jar $InspectorSource $JdbcUrl $LatestMigrationVersion 2>&1)",
                "$NativeExitCode = $LASTEXITCODE",
                "$ErrorActionPreference = $PreviousErrorActionPreference");
        assertThat(function).doesNotContain(
                "$env:ComSpec", "$Command =", "org.h2.tools.Shell", "-sql", "-password", "flyway_schema_history");
        assertThat(inspector).contains(
                "DriverManager.getConnection(jdbcUrl, \"sa\", \"\")",
                "\\\"flyway_schema_history\\\"",
                "\\\"success\\\"",
                "\\\"version\\\"");
        String startup = Files.readString(Path.of("scripts", "start-local.ps1"));
        assertThat(startup).contains(". (Join-Path $PSScriptRoot 'startup-h2-inspection.ps1')");
        assertThat(startup).doesNotContain(
                "-sql", "-password", "$StateSql", "$PresenceSql", "flyway_schema_history");
        assertThat(Files.readString(Path.of("scripts", "windows-startup-gates.ps1"))).contains(
                "function Test-WindowsPowerShell51FlywayInspector",
                ". (Join-Path $PSScriptRoot 'scripts\\startup-h2-inspection.ps1')",
                "powershell.exe",
                "Windows PowerShell 5.1 inspector regression");
        assertThat(Files.readString(Path.of(".github", "workflows", "windows-startup-smoke.yml")))
                .contains("- scripts/startup-h2-inspection.ps1", "- scripts/FlywayStateInspector.java");
    }

    @Test
    void backupCollectionsAreArrayWrappedAtEveryCallSite() throws Exception {
        List<String> backupCalls = executableLines(Path.of("scripts", "windows-startup-gates.ps1")).stream()
                .filter(line -> line.contains("Get-CompletedBackups") || line.contains("Get-PartialBackups"))
                .filter(line -> !line.startsWith("function Get-"))
                .toList();

        assertThat(backupCalls).isNotEmpty().allMatch(line -> line.matches(
                "^\\$[A-Za-z][A-Za-z0-9]* = @\\(Get-(Completed|Partial)Backups \\$[A-Za-z][A-Za-z0-9]*Root\\)$"));
    }

    @Test
    void productionBackupHashingUsesStreamingDotNetSha256WithoutModuleAutoload() throws Exception {
        Path hashingPath = Path.of("scripts", "startup-file-hashing.ps1");
        List<String> hashingLines = Files.exists(hashingPath) ? executableLines(hashingPath) : List.of();
        List<String> startupLines = executableLines(Path.of("scripts", "start-local.ps1"));
        List<String> gateLines = executableLines(Path.of("scripts", "windows-startup-gates.ps1"));

        assertThat(startupLines.stream().filter(line -> line.contains("Get-FileHash"))).isEmpty();
        assertThat(hashingLines).contains(
                "function Get-Sha256Hex([string]$Path) {",
                "$Stream = [System.IO.File]::OpenRead($Path)",
                "$Sha256 = [System.Security.Cryptography.SHA256]::Create()",
                "$HashBytes = $Sha256.ComputeHash($Stream)",
                "return [System.BitConverter]::ToString($HashBytes).Replace('-', '')",
                "$Sha256.Dispose()",
                "$Stream.Dispose()");
        assertThat(startupLines).contains(
                ". (Join-Path $PSScriptRoot 'startup-file-hashing.ps1')",
                "$SourceHash = Get-Sha256Hex -Path $DatabaseFile.FullName",
                "$CopyHash = Get-Sha256Hex -Path $Copy");
        assertThat(gateLines).contains(
                ". (Join-Path $Scenario 'scripts\\startup-file-hashing.ps1')",
                "$ActualHash = Get-Sha256Hex -Path $Fixture",
                "Assert-Equal 'FF5D8507B6A72BEE2DEBCE2C0054798DEACCDC5D8A1B945B6280CE8AA9CBA52E' $ActualHash 'The startup SHA-256 helper returned the wrong digest.'");
    }

    private static List<String> executableLines(Path path) throws Exception {
        return Files.readAllLines(path).stream()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .toList();
    }
}

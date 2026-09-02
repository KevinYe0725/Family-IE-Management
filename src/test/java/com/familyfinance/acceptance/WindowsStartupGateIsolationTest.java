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
                .filter(line -> line.contains("$SourceRoot") || line.contains("$PSScriptRoot"))
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
                "CASE WHEN EXISTS (...) THEN 'FLYWAY_HISTORY_PRESENT' ELSE 'FLYWAY_HISTORY_ABSENT' END",
                "FLYWAY_HISTORY_ABSENT",
                "(1 row, 3 ms)");
        Set<String> allowed = Set.of("FLYWAY_HISTORY_PRESENT", "FLYWAY_HISTORY_ABSENT");
        assertThat(representativeAbsentOutput.stream()
                        .map(String::trim)
                        .filter(allowed::contains))
                .containsExactly("FLYWAY_HISTORY_ABSENT");

        assertThat(parserLines).contains(
                "$StatusLines = @($Output |",
                "$_ -ceq 'FLYWAY_HISTORY_PRESENT' -or",
                "$_ -ceq 'FLYWAY_HISTORY_ABSENT'",
                "if ($StatusLines.Count -ne 1) {",
                "return ($StatusLines[0] -ceq 'FLYWAY_HISTORY_PRESENT')");
        assertThat(parserLines.stream()
                        .filter(line -> line.contains("-match") && line.contains("FLYWAY_HISTORY_")))
                .isEmpty();
        assertThat(startupLines).contains(
                ". (Join-Path $PSScriptRoot 'startup-output-parsing.ps1')",
                "$Sql = \"select case when exists (select 1 from information_schema.tables where table_schema = 'PUBLIC' and table_name = 'flyway_schema_history') then 'FLYWAY_HISTORY_PRESENT' else 'FLYWAY_HISTORY_ABSENT' end as HISTORY_STATUS\"",
                "$HasHistory = ConvertFrom-FlywayHistoryShellOutput -Output @($Output)",
                "return $HasHistory");
        assertThat(gateLines).contains(
                ". (Join-Path $Scenario 'scripts\\startup-output-parsing.ps1')",
                "$Parsed = ConvertFrom-FlywayHistoryShellOutput -Output $RepresentativeAbsentOutput",
                "Assert-True (-not $Parsed) 'The H2 Shell header produced a false Flyway-history positive.'",
                "Assert-Equal 4 $RejectedInvalidOutputs 'The Flyway-history parser accepted a zero, duplicate, multiple, or ambiguous status.'");
    }

    @Test
    void backupCollectionsAreArrayWrappedAtEveryCallSite() throws Exception {
        List<String> backupCalls = executableLines(Path.of("scripts", "windows-startup-gates.ps1")).stream()
                .filter(line -> line.contains("Get-CompletedBackups") || line.contains("Get-PartialBackups"))
                .filter(line -> !line.startsWith("function Get-"))
                .toList();

        assertThat(backupCalls).isNotEmpty().allMatch(line -> line.matches(
                "^\\$[A-Za-z][A-Za-z0-9]* = @\\(Get-(Completed|Partial)Backups \\$BackupRoot\\)$"));
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

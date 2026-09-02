package com.familyfinance.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}

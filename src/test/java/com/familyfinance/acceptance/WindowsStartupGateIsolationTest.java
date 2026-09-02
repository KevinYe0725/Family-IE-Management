package com.familyfinance.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}

package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlywayStateInspectorTest {

    private static final List<String> STATES = List.of(
            "NO_HISTORY", "BEHIND_CURRENT", "CURRENT", "FAILED", "FUTURE", "AMBIGUOUS");

    @TempDir Path tempDir;

    @Test
    void sourceInspectorClassifiesEveryRealH2HistoryStateWithOneOutputLine() throws Exception {
        Path noHistory = StageOneDatabaseFixture.create(tempDir.resolve("no-history"));
        assertState(noHistory, 8, "NO_HISTORY");

        Path normal = StageOneDatabaseFixture.create(tempDir.resolve("normal"));
        MigrationStateFixtureCli.main(new String[] {normal.toString(), "migrate-to-7"});
        assertState(normal, 8, "BEHIND_CURRENT");
        assertState(normal, 7, "CURRENT");

        Path failed = StageOneDatabaseFixture.create(tempDir.resolve("failed"));
        MigrationStateFixtureCli.main(new String[] {failed.toString(), "migrate-to-6"});
        withExpectedFlywayErrorSuppressed(() ->
                MigrationStateFixtureCli.main(new String[] {failed.toString(), "fail-v7-budget"}));
        assertState(failed, 8, "FAILED");

        Path future = StageOneDatabaseFixture.create(tempDir.resolve("future"));
        MigrationStateFixtureCli.main(new String[] {future.toString(), "migrate-to-7"});
        MigrationStateFixtureCli.main(new String[] {future.toString(), "make-future-history"});
        assertState(future, 8, "FUTURE");

        Path ambiguous = StageOneDatabaseFixture.create(tempDir.resolve("ambiguous"));
        MigrationStateFixtureCli.main(new String[] {ambiguous.toString(), "migrate-to-7"});
        MigrationStateFixtureCli.main(new String[] {ambiguous.toString(), "make-ambiguous-history"});
        assertState(ambiguous, 8, "AMBIGUOUS");

        assertThat(Files.exists(Path.of("scripts", "FlywayStateInspector.class"))).isFalse();
    }

    @Test
    void sourceInspectorReturnsNonzeroAndNoStateForInvalidDatabase() throws Exception {
        ProcessResult result = inspect(tempDir.resolve("missing"), 7);
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.lines()).noneMatch(STATES::contains);
        assertThat(Files.exists(Path.of("scripts", "FlywayStateInspector.class"))).isFalse();
    }

    private void assertState(Path database, long latest, String expected) throws Exception {
        ProcessResult result = inspect(database, latest);
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.lines()).containsExactly(expected);
    }

    private ProcessResult inspect(Path database, long latest) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path h2Jar = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path source = Path.of("scripts", "FlywayStateInspector.java").toAbsolutePath();
        String jdbcUrl = MigrationTestSupport.h2Url(database) + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
        Process process = new ProcessBuilder(
                        java.toString(), "-cp", h2Jar.toString(), source.toString(), jdbcUrl, Long.toString(latest))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        List<String> lines = output.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        return new ProcessResult(exitCode, output, lines);
    }

    private static void withExpectedFlywayErrorSuppressed(ThrowingAction action) throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("org.flywaydb.core.internal.command.DbMigrate");
        ch.qos.logback.classic.Level previous = logger.getLevel();
        try {
            logger.setLevel(ch.qos.logback.classic.Level.OFF);
            action.run();
        } finally {
            logger.setLevel(previous);
        }
    }

    private record ProcessResult(int exitCode, String output, List<String> lines) {}

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}

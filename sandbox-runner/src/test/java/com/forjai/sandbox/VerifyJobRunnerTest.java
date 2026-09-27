package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VerifyJobRunnerTest {

    @TempDir
    Path products;

    @TempDir
    Path work;

    private final List<List<String>> calls = new ArrayList<>();

    private VerifyJobRunner runner(ProcessExecutor executor) {
        return new VerifyJobRunner(products, work, new PodmanCommandBuilder("unix:///s", java.nio.file.Path.of("/deps")), executor);
    }

    /** Checkout siempre OK (simula git archive + tar); los pasos de podman responden según `stepResult`. */
    private ProcessExecutor fake(java.util.function.Function<String, ProcessExecutor.Execution> stepResult) {
        return (command, directory, timeout) -> {
            calls.add(command);
            if (command.get(0).equals("git") || command.get(0).equals("tar")) {
                return new ProcessExecutor.Execution(0, "", false);
            }
            return stepResult.apply(command.get(command.size() - 1));
        };
    }

    @Test
    void allStepsPassInOrder() {
        var result = runner(fake(step -> new ProcessExecutor.Execution(0, step + " ok", false)))
                .verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP);

        assertEquals("PASS", result.overall());
        assertEquals(List.of("checkout", "restore", "build", "test", "smoke"),
                result.steps().stream().map(SandboxResult.StepResult::name).toList());
        assertTrue(result.steps().stream().allMatch(s -> s.status().equals("PASS")));
    }

    @Test
    void aFailingStepStopsTheRestAsSkipped() {
        var result = runner(fake(step -> step.equals("build")
                ? new ProcessExecutor.Execution(1, "error CS1002: ; expected", false)
                : new ProcessExecutor.Execution(0, "", false)))
                .verify("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME);

        assertEquals("FAIL", result.overall());
        assertEquals("FAIL", result.steps().get(2).status());
        assertTrue(result.steps().get(2).outputTail().contains("CS1002"));
        assertEquals("SKIPPED", result.steps().get(3).status());
        assertEquals("SKIPPED", result.steps().get(4).status());
    }

    @Test
    void aTimedOutStepIsReportedAsTimeout() {
        var result = runner(fake(step -> step.equals("smoke")
                ? new ProcessExecutor.Execution(-1, "", true)
                : new ProcessExecutor.Execution(0, "", false)))
                .verify("M-1", "a".repeat(40), ExecutionProfile.FLUTTER_WEB_APP);

        assertEquals("TIMEOUT", result.steps().get(4).status());
        assertEquals("FAIL", result.overall());
    }

    @Test
    void theTestStepCarriesTheParsedCounts() throws Exception {
        var result = runner((command, directory, timeout) -> {
            if (command.get(command.size() - 1).equals("test")) {
                var jobDir = Path.of(command.stream().filter(a -> a.endsWith(":/work:Z")).findFirst().orElseThrow()
                        .replace(":/work:Z", ""));
                try {
                    Files.createDirectories(jobDir.resolve(".forjai"));
                    Files.writeString(jobDir.resolve(".forjai/results.trx"),
                            "<Counters total=\"3\" passed=\"3\" failed=\"0\" />");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return new ProcessExecutor.Execution(0, "", false);
        }).verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP);

        assertEquals(3, result.steps().get(3).testsPassed());
    }

    // Review Focus: el commit no existe → FAIL en "checkout", no una excepción.
    @Test
    void aMissingCommitFailsTheCheckoutStep() {
        var result = runner((command, directory, timeout) -> command.get(0).equals("git")
                ? new ProcessExecutor.Execution(128, "fatal: not a valid object name", false)
                : new ProcessExecutor.Execution(0, "", false))
                .verify("M-1", "b".repeat(40), ExecutionProfile.DOTNET_APP);

        assertEquals("FAIL", result.steps().get(0).status());
        assertTrue(result.steps().subList(1, 5).stream().allMatch(s -> s.status().equals("SKIPPED")));
    }

    // Review Focus: salidas gigantes se acotan a 20 KB conservando el final (donde está el error).
    @Test
    void theOutputTailKeepsTheEndAndIsBounded() {
        var huge = "x".repeat(50_000) + "ERROR AL FINAL";
        var tail = VerifyJobRunner.tail(huge, 20_000);
        assertEquals(20_000, tail.length());
        assertTrue(tail.endsWith("ERROR AL FINAL"));
    }

    @Test
    void theWorkDirectoryIsDeletedAfterTheJob() throws Exception {
        runner(fake(step -> new ProcessExecutor.Execution(0, "", false)))
                .verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP);
        try (var entries = Files.list(work)) {
            assertEquals(0, entries.count());
        }
    }
}

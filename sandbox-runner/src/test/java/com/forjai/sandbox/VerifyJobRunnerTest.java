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

    // Spec 2026-10-02 B1 §3: solo se extrae la carpeta del componente pedido.
    @Test
    void componentDirArchivesOnlyThatTree() {
        runner(fake(step -> new ProcessExecutor.Execution(0, "", false)))
                .verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP, "backend");
        assertTrue(calls.stream().anyMatch(c -> c.get(0).equals("git") && c.contains("a".repeat(40) + ":backend")),
                calls.toString());
    }

    @Test
    void unknownComponentDirFailsTheCheckout() {
        var result = runner(fake(step -> new ProcessExecutor.Execution(0, "", false)))
                .verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP, "../etc");
        assertEquals("FAIL", result.overall());
        assertTrue(result.steps().get(0).outputTail().contains("componentDir"), result.steps().get(0).outputTail());
    }

    /** Fake de podman para PACKAGE: escribe los status y un archivo de salida en /work. */
    private ProcessExecutor packFake(java.util.Map<String, String> statusByTarget) {
        return packFake(statusByTarget, (args, workDir) -> { });
    }

    private ProcessExecutor packFake(java.util.Map<String, String> statusByTarget,
                                     java.util.function.BiConsumer<List<String>, Path> hook) {
        return (command, directory, timeout) -> {
            if (command.get(0).equals("git") || command.get(0).equals("tar")) {
                return new ProcessExecutor.Execution(0, "", false);
            }
            if (!command.get(command.size() - 1).equals("package")) {
                return new ProcessExecutor.Execution(0, "", false);
            }
            var workDir = Path.of(command.stream().filter(a -> a.endsWith(":/work:Z")).findFirst().orElseThrow()
                    .replace(":/work:Z", ""));
            hook.accept(command, workDir);
            try {
                var pkg = workDir.resolve(".forjai/package");
                Files.createDirectories(pkg.resolve("status"));
                for (var e : statusByTarget.entrySet()) {
                    Files.writeString(pkg.resolve("status").resolve(e.getKey()), e.getValue());
                }
                Files.createDirectories(pkg.resolve("juego/windows"));
                Files.writeString(pkg.resolve("juego/windows/Juego.exe"), "exe");
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            return new ProcessExecutor.Execution(0, "listo", false);
        };
    }

    private static String readEnvFile(List<String> args) {
        try {
            return Files.readString(Path.of(args.get(args.indexOf("--env-file") + 1)));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void packCopiesTheOutputAndReportsEachTarget() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var runner = new VerifyJobRunner(products, work, new PodmanCommandBuilder("unix:///s", Path.of("/deps")), packFake(java.util.Map.of(
                "windows", "PASS", "android-apk", "FAIL: falta el SDK")), staging);
        var result = runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 2,
                List.of(PackageTarget.WINDOWS, PackageTarget.ANDROID_APK), null);
        assertEquals(List.of("checkout", "restore", "package:windows", "package:android-apk"),
                result.steps().stream().map(SandboxResult.StepResult::name).toList());
        assertEquals("PASS", result.steps().get(2).status());
        assertEquals("FAIL", result.steps().get(3).status());
        assertTrue(result.steps().get(3).outputTail().contains("falta el SDK"));
        assertEquals("FAIL", result.overall());
        assertTrue(Files.exists(staging.resolve("M-1/incremento-2/juego/windows/Juego.exe")));
        assertFalse(Files.exists(staging.resolve("M-1/incremento-2/status")));
    }

    @Test
    void theReleaseKeyIsWrittenForTheJobAndNeverCopied() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        var runner = new VerifyJobRunner(products, work, new PodmanCommandBuilder("unix:///s", Path.of("/deps")), packFake(java.util.Map.of(
                "android-aab", "PASS"), (args, workDir) -> seen.set(readEnvFile(args))), staging);
        runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 3, List.of(PackageTarget.ANDROID_AAB),
                new AndroidRelease(java.util.Base64.getEncoder().encodeToString("jks".getBytes()), "forjai", "s3cr3t"));
        assertTrue(seen.get().contains("GODOT_ANDROID_KEYSTORE_RELEASE_PATH=/work/.forjai/keystore/release.jks"));
        assertTrue(seen.get().contains("GODOT_ANDROID_KEYSTORE_RELEASE_USER=forjai"));
        assertTrue(seen.get().contains("FORJAI_TARGETS=android-aab"));
        assertFalse(Files.exists(staging.resolve("M-1/incremento-3/keystore")));
        try (var files = Files.list(work)) { assertEquals(0, files.count()); }
    }

    @Test
    void packSkipsSymlinksPlantedByTheContainer() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var outside = Files.writeString(Files.createTempFile("host", ".txt"), "secreto del host");
        var runner = new VerifyJobRunner(products, work, new PodmanCommandBuilder("unix:///s", Path.of("/deps")), packFake(java.util.Map.of(
                "windows", "PASS"), (args, workDir) -> {
            try {
                var pkg = workDir.resolve(".forjai/package");
                Files.createDirectories(pkg.resolve("status"));
                Files.createDirectories(pkg.resolve("juego"));
                Files.createSymbolicLink(pkg.resolve("juego/link"), outside);
                Files.createSymbolicLink(pkg.resolve("status/linux"), outside);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }), staging);
        var result = runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 1,
                List.of(PackageTarget.WINDOWS, PackageTarget.LINUX), null);
        assertFalse(Files.exists(staging.resolve("M-1/incremento-1/juego/link"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(staging.resolve("M-1/incremento-1/juego/windows/Juego.exe")));
        assertEquals("FAIL", result.steps().get(3).status());
        assertTrue(result.steps().get(3).outputTail().contains("estado inválido"));
    }

    @Test
    void packRejectsAReleaseKeyThatCouldInjectEnvVariables() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var packed = new java.util.concurrent.atomic.AtomicBoolean();
        var runner = new VerifyJobRunner(products, work, new PodmanCommandBuilder("unix:///s", Path.of("/deps")), packFake(java.util.Map.of(
                "android-aab", "PASS"), (args, workDir) -> packed.set(true)), staging);
        var result = runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 3, List.of(PackageTarget.ANDROID_AAB),
                new AndroidRelease(java.util.Base64.getEncoder().encodeToString("jks".getBytes()), "forjai", "x\nFORJAI_TARGETS=linux"));
        assertFalse(packed.get());
        assertEquals("FAIL", result.overall());
        assertEquals("package:android-aab", result.steps().get(1).name());
        assertTrue(result.steps().get(1).outputTail().contains("clave de Android inválida"));
        try (var files = Files.list(work)) { assertEquals(0, files.count()); }
    }
}

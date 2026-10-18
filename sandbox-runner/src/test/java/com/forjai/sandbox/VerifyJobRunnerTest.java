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
        var out = staging.resolve("M-1").resolve(result.stagingDir());
        assertTrue(Files.exists(out.resolve("juego/windows/Juego.exe")));
        assertFalse(Files.exists(out.resolve("status")));
    }

    @Test
    void theReleaseKeyIsWrittenForTheJobAndNeverCopied() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        var runner = new VerifyJobRunner(products, work, new PodmanCommandBuilder("unix:///s", Path.of("/deps")), packFake(java.util.Map.of(
                "android-aab", "PASS"), (args, workDir) -> seen.set(readEnvFile(args))), staging);
        var result = runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 3, List.of(PackageTarget.ANDROID_AAB),
                new AndroidRelease(java.util.Base64.getEncoder().encodeToString("jks".getBytes()), "forjai", "s3cr3t"));
        assertTrue(seen.get().contains("GODOT_ANDROID_KEYSTORE_RELEASE_PATH=/work/.forjai/keystore/release.jks"));
        assertTrue(seen.get().contains("GODOT_ANDROID_KEYSTORE_RELEASE_USER=forjai"));
        assertTrue(seen.get().contains("FORJAI_TARGETS=android-aab"));
        assertFalse(Files.exists(staging.resolve("M-1").resolve(result.stagingDir()).resolve("keystore")));
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
        var out = staging.resolve("M-1").resolve(result.stagingDir());
        assertFalse(Files.exists(out.resolve("juego/link"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(out.resolve("juego/windows/Juego.exe")));
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

    private VerifyJobRunner packRunner(Path staging, ProcessExecutor executor) {
        return new VerifyJobRunner(products, work, new PodmanCommandBuilder("unix:///s", Path.of("/deps")), executor, staging);
    }

    // Revisión final (3): dos empaquetados del mismo incremento nunca comparten el staging.
    @Test
    void eachPackJobHasItsOwnStagingDirectory() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var runner = packRunner(staging, packFake(java.util.Map.of("windows", "PASS")));
        var first = runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 2, List.of(PackageTarget.WINDOWS), null);
        var second = runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 2, List.of(PackageTarget.WINDOWS), null);
        assertTrue(first.stagingDir().matches("incremento-2-[0-9a-f-]{36}"), first.stagingDir());
        assertNotEquals(first.stagingDir(), second.stagingDir());
        assertTrue(Files.exists(staging.resolve("M-1").resolve(first.stagingDir()).resolve("juego/windows/Juego.exe")));
        assertTrue(Files.exists(staging.resolve("M-1").resolve(second.stagingDir()).resolve("juego/windows/Juego.exe")));
        assertNull(runner(fake(step -> new ProcessExecutor.Execution(0, "", false)))
                .verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP).stagingDir());
    }

    // Revisión final (5): con timeout, los targets que ya escribieron su estado lo conservan.
    @Test
    void aTimeoutOnlyMarksTheTargetsThatDidNotFinish() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var finished = packFake(java.util.Map.of("windows", "PASS", "macos", "FAIL: sin plantilla"));
        ProcessExecutor timingOut = (command, directory, timeout) -> {
            var e = finished.run(command, directory, timeout);
            return command.get(command.size() - 1).equals("package") ? new ProcessExecutor.Execution(-1, "cortado", true) : e;
        };
        var result = packRunner(staging, timingOut).pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 1,
                List.of(PackageTarget.WINDOWS, PackageTarget.MACOS, PackageTarget.LINUX), null);
        assertEquals(List.of("PASS", "FAIL", "TIMEOUT"), result.steps().subList(2, 5).stream().map(SandboxResult.StepResult::status).toList());
        assertTrue(Files.exists(staging.resolve("M-1").resolve(result.stagingDir()).resolve("juego/windows/Juego.exe")));
    }

    @Test
    void thePackageStepFitsAllTargetsWithinTheClientTimeout() {
        assertEquals(1800, ExecutionProfile.PACKAGE_STEP.timeoutSeconds());
    }

    static final AndroidRelease RELEASE = new AndroidRelease(
            java.util.Base64.getEncoder().encodeToString("KEYSTORE-BYTES-0123456789".getBytes()), "subidas", "Cl4veSecreta");

    // Revisión final (7): la contraseña y el alias nunca salen en un motivo ni en la salida.
    @Test
    void thePasswordAndAliasAreRedactedFromEveryOutput() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var base = packFake(java.util.Map.of("android-aab", "FAIL: keystore subidas con Cl4veSecreta inválida"));
        ProcessExecutor leaky = (command, directory, timeout) -> {
            var e = base.run(command, directory, timeout);
            return command.get(command.size() - 1).equals("package")
                    ? new ProcessExecutor.Execution(0, "pass=Cl4veSecreta alias=subidas", false) : e;
        };
        var result = packRunner(staging, leaky).pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 3,
                List.of(PackageTarget.WINDOWS, PackageTarget.ANDROID_AAB), RELEASE);
        for (var s : result.steps()) {
            assertFalse(s.outputTail().contains("Cl4veSecreta"), s.name() + ": " + s.outputTail());
            assertFalse(s.outputTail().contains("subidas"), s.name() + ": " + s.outputTail());
        }
        assertTrue(result.steps().stream().anyMatch(s -> s.outputTail().contains("***")));
    }

    @Test
    void anOutputFileWithKeyMaterialIsDiscardedAndAndroidFails() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var runner = packRunner(staging, packFake(java.util.Map.of("windows", "PASS", "android-aab", "PASS"), (args, workDir) -> {
            try {
                var android = Files.createDirectories(workDir.resolve(".forjai/package/juego/android"));
                Files.writeString(android.resolve("Juego.aab"), "zip...Cl4veSecreta...zip");
                Files.write(android.resolve("copia.jks"), Files.readAllBytes(workDir.resolve(".forjai/keystore/release.jks")));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }));
        var result = runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 3,
                List.of(PackageTarget.WINDOWS, PackageTarget.ANDROID_AAB), RELEASE);
        var out = staging.resolve("M-1").resolve(result.stagingDir());
        assertFalse(Files.exists(out.resolve("juego/android/Juego.aab")));
        assertFalse(Files.exists(out.resolve("juego/android/copia.jks")));
        assertTrue(Files.exists(out.resolve("juego/windows/Juego.exe")));
        assertEquals("PASS", result.steps().get(2).status());
        assertEquals("FAIL", result.steps().get(3).status());
        assertTrue(result.steps().get(3).outputTail().contains("la salida contenía material de la clave"));
    }

    @Test
    void aDesktopTargetWhoseFilesCarriedKeyMaterialAlsoFails() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var runner = packRunner(staging, packFake(java.util.Map.of("windows", "PASS", "linux", "PASS", "android-aab", "PASS"),
                (args, workDir) -> {
                    try {
                        var windows = Files.createDirectories(workDir.resolve(".forjai/package/juego/windows"));
                        Files.writeString(windows.resolve("Juego.pck"), "...Cl4veSecreta...");
                        Files.createDirectories(workDir.resolve(".forjai/package/juego/linux"));
                        Files.writeString(workDir.resolve(".forjai/package/juego/linux/Juego.x86_64"), "elf");
                    } catch (java.io.IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                }));
        var result = runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 3,
                List.of(PackageTarget.WINDOWS, PackageTarget.LINUX, PackageTarget.ANDROID_AAB), RELEASE);
        assertEquals(List.of("FAIL", "PASS", "FAIL"), result.steps().subList(2, 5).stream().map(SandboxResult.StepResult::status).toList());
        assertFalse(Files.exists(staging.resolve("M-1").resolve(result.stagingDir()).resolve("juego/windows/Juego.pck")));
    }

    @Test
    void theReleaseKeyIsNotInTheJobDirDuringRestore() throws Exception {
        var staging = Files.createTempDirectory("staging");
        var seenDuringRestore = new java.util.concurrent.atomic.AtomicBoolean();
        var base = packFake(java.util.Map.of("android-aab", "PASS"));
        ProcessExecutor executor = (command, directory, timeout) -> {
            if (command.get(command.size() - 1).equals("restore")) {
                var workDir = Path.of(command.stream().filter(a -> a.endsWith(":/work:Z")).findFirst().orElseThrow()
                        .replace(":/work:Z", ""));
                seenDuringRestore.set(Files.exists(workDir.resolve(".forjai/keystore")));
                assertFalse(command.contains("--env-file"));
            }
            return base.run(command, directory, timeout);
        };
        packRunner(staging, executor).pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 3,
                List.of(PackageTarget.ANDROID_AAB), RELEASE);
        assertFalse(seenDuringRestore.get());
    }

    // Revisión final (7): un reinicio a mitad de un job no deja la clave ni el checkout en work-root.
    @Test
    void orphanJobAndEnvFilesAreRemovedAtStartup() throws Exception {
        Files.createDirectories(work.resolve("job-viejo/.forjai/keystore"));
        Files.writeString(work.resolve("job-viejo/.forjai/keystore/release.jks"), "k");
        Files.writeString(work.resolve("env-viejo"), "GODOT_ANDROID_KEYSTORE_RELEASE_PASSWORD=x");
        Files.writeString(work.resolve("otro.txt"), "queda");
        runner(fake(step -> new ProcessExecutor.Execution(0, "", false))).cleanOrphans();
        try (var files = Files.list(work)) {
            assertEquals(List.of("otro.txt"), files.map(p -> p.getFileName().toString()).toList());
        }
    }
}

package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * VERIFY (spec 2026-09-26 §2): extrae el commit exacto a un directorio temporal
 * (nunca toca el repo original), corre los pasos fijos del perfil en
 * contenedores aislados, y borra todo al terminar.
 */
public class VerifyJobRunner {

    static final int OUTPUT_TAIL_CHARS = 20_000;

    private final Path productsRoot;
    private final Path workRoot;
    private final PodmanCommandBuilder podman;
    private final ProcessExecutor executor;

    public VerifyJobRunner(Path productsRoot, Path workRoot, PodmanCommandBuilder podman, ProcessExecutor executor) {
        this.productsRoot = productsRoot;
        this.workRoot = workRoot;
        this.podman = podman;
        this.executor = executor;
    }

    public SandboxResult verify(String missionId, String commitSha, ExecutionProfile profile) {

        var steps = new ArrayList<SandboxResult.StepResult>();
        var jobDir = workRoot.resolve("job-" + UUID.randomUUID());

        try {
            Files.createDirectories(jobDir);

            var checkout = checkout(missionId, commitSha, jobDir);
            steps.add(checkout);
            var failed = !SandboxResult.PASS.equals(checkout.status());

            for (var step : profile.steps()) {
                if (failed) {
                    steps.add(new SandboxResult.StepResult(step.name(), SandboxResult.SKIPPED, 0, 0,
                            "Omitido: un paso anterior falló.", 0, 0));
                    continue;
                }
                var result = runStep(profile, step, jobDir);
                steps.add(result);
                failed = !SandboxResult.PASS.equals(result.status());
            }

            var overall = steps.stream().allMatch(s -> SandboxResult.PASS.equals(s.status()))
                    ? SandboxResult.PASS : SandboxResult.FAIL;
            return new SandboxResult(overall, steps);

        } catch (IOException ex) {
            steps.add(new SandboxResult.StepResult("checkout", SandboxResult.FAIL, -1, 0,
                    "No se pudo preparar el directorio de trabajo: " + ex.getMessage(), 0, 0));
            return new SandboxResult(SandboxResult.FAIL, steps);
        } finally {
            deleteQuietly(jobDir);
        }
    }

    private SandboxResult.StepResult checkout(String missionId, String commitSha, Path jobDir) {

        var started = System.currentTimeMillis();
        var repo = productsRoot.resolve(missionId).normalize();

        if (!repo.startsWith(productsRoot) || !commitSha.matches("[0-9a-f]{40}")) {
            return new SandboxResult.StepResult("checkout", SandboxResult.FAIL, -1, 0,
                    "missionId o commitSha inválidos.", 0, 0);
        }

        var tar = jobDir.resolve(".forjai-src.tar");
        var archive = executor.run(List.of("git", "-c", "safe.directory=*", "-C", repo.toString(), "archive",
                "--format=tar", "-o", tar.toString(), commitSha), jobDir, 60);
        if (archive.exitCode() != 0) {
            return new SandboxResult.StepResult("checkout", SandboxResult.FAIL, archive.exitCode(),
                    System.currentTimeMillis() - started, tail(archive.output(), OUTPUT_TAIL_CHARS), 0, 0);
        }

        var extract = executor.run(List.of("tar", "-xf", tar.toString(), "-C", jobDir.toString()), jobDir, 60);
        try {
            Files.deleteIfExists(tar);
        } catch (IOException ignored) {
            // el directorio completo se borra al final del job
        }

        var status = extract.exitCode() == 0 ? SandboxResult.PASS : SandboxResult.FAIL;
        return new SandboxResult.StepResult("checkout", status, extract.exitCode(),
                System.currentTimeMillis() - started, tail(extract.output(), OUTPUT_TAIL_CHARS), 0, 0);
    }

    private SandboxResult.StepResult runStep(ExecutionProfile profile, ExecutionProfile.Step step, Path jobDir) {

        var started = System.currentTimeMillis();
        var execution = executor.run(podman.run(profile, step, jobDir), jobDir, step.timeoutSeconds() + 30L);
        var duration = System.currentTimeMillis() - started;

        var status = execution.timedOut() ? SandboxResult.TIMEOUT
                : execution.exitCode() == 0 ? SandboxResult.PASS : SandboxResult.FAIL;

        var counts = "test".equals(step.name())
                ? TestReportParser.parse(jobDir) : new TestReportParser.TestCounts(0, 0);

        return new SandboxResult.StepResult(step.name(), status, execution.exitCode(), duration,
                tail(execution.output(), OUTPUT_TAIL_CHARS), counts.passed(), counts.failed());
    }

    static String tail(String output, int maxChars) {
        if (output == null) {
            return "";
        }
        return output.length() <= maxChars ? output : output.substring(output.length() - maxChars);
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // best-effort: un directorio huérfano no debe cambiar el resultado del job
        }
    }
}

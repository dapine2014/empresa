package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
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

    private final Path stagingRoot;

    public VerifyJobRunner(Path productsRoot, Path workRoot, PodmanCommandBuilder podman, ProcessExecutor executor) {
        this(productsRoot, workRoot, podman, executor, workRoot.resolve(".staging"));
    }

    public VerifyJobRunner(Path productsRoot, Path workRoot, PodmanCommandBuilder podman, ProcessExecutor executor,
                           Path stagingRoot) {
        this.stagingRoot = stagingRoot;
        this.productsRoot = productsRoot;
        this.workRoot = workRoot;
        this.podman = podman;
        this.executor = executor;
    }

    /** Spec 2026-10-02 B1 §3: carpetas de componente admitidas (lista fija, como los perfiles). */
    static final java.util.Set<String> COMPONENT_DIRS = java.util.Set.of("", "backend", "app", "game-client");

    public SandboxResult verify(String missionId, String commitSha, ExecutionProfile profile) {
        return verify(missionId, commitSha, profile, "");
    }

    public SandboxResult verify(String missionId, String commitSha, ExecutionProfile profile, String componentDir) {

        var steps = new ArrayList<SandboxResult.StepResult>();
        var jobDir = workRoot.resolve("job-" + UUID.randomUUID());

        try {
            Files.createDirectories(jobDir);

            var checkout = checkout(missionId, commitSha, componentDir, jobDir);
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

    /**
     * PACKAGE (entrega del producto, spec 2026-10-17 §1): mismo checkout y restore que VERIFY, luego el paso fijo
     * {@code package} con los targets pedidos. La salida queda en {@code stagingRoot/<missionId>/incremento-<N>};
     * la clave de publicación viaja por env file (permisos 600) y nunca se copia ni se loguea.
     */
    public SandboxResult pack(String missionId, String commitSha, ExecutionProfile profile, int increment,
                              List<PackageTarget> targets, AndroidRelease release) {

        var steps = new ArrayList<SandboxResult.StepResult>();
        var jobDir = workRoot.resolve("job-" + UUID.randomUUID());
        var envFile = workRoot.resolve("env-" + UUID.randomUUID());

        try {
            Files.createDirectories(jobDir);

            var checkout = checkout(missionId, commitSha, "", jobDir);
            steps.add(checkout);
            if (!SandboxResult.PASS.equals(checkout.status())) {
                steps.add(skipped("restore"));
                targets.forEach(t -> steps.add(skipped("package:" + t.id())));
                return new SandboxResult(SandboxResult.FAIL, steps);
            }

            if (release != null && (unsafeEnvValue(release.alias()) || unsafeEnvValue(release.password()))) {
                targets.forEach(t -> steps.add(new SandboxResult.StepResult("package:" + t.id(), SandboxResult.FAIL, -1, 0,
                        "clave de Android inválida (caracteres no permitidos)", 0, 0)));
                return new SandboxResult(SandboxResult.FAIL, steps);
            }

            var env = new StringBuilder("FORJAI_TARGETS=")
                    .append(String.join(",", targets.stream().map(PackageTarget::id).toList())).append('\n');
            if (release != null) {
                var keystore = jobDir.resolve(".forjai/keystore/release.jks");
                Files.createDirectories(keystore.getParent());
                Files.write(keystore, Base64.getDecoder().decode(release.keystoreBase64()));
                env.append("GODOT_ANDROID_KEYSTORE_RELEASE_PATH=/work/.forjai/keystore/release.jks\n")
                        .append("GODOT_ANDROID_KEYSTORE_RELEASE_USER=").append(release.alias()).append('\n')
                        .append("GODOT_ANDROID_KEYSTORE_RELEASE_PASSWORD=").append(release.password()).append('\n');
            }
            try {
                Files.createFile(envFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException ignored) {
                // el sistema de archivos no tiene permisos POSIX
                Files.createFile(envFile);
            }
            Files.writeString(envFile, env.toString());

            var restore = runStep(profile, profile.steps().get(0), jobDir);
            steps.add(restore);
            if (!SandboxResult.PASS.equals(restore.status())) {
                targets.forEach(t -> steps.add(skipped("package:" + t.id())));
                return new SandboxResult(SandboxResult.FAIL, steps);
            }

            var step = ExecutionProfile.PACKAGE_STEP;
            var started = System.currentTimeMillis();
            var execution = executor.run(podman.run(profile, step, jobDir, envFile), jobDir, step.timeoutSeconds() + 30L);
            var duration = System.currentTimeMillis() - started;
            var stepTail = tail(execution.output(), OUTPUT_TAIL_CHARS);

            var packageDir = jobDir.resolve(".forjai/package");
            for (var target : targets) {
                var name = "package:" + target.id();
                if (execution.timedOut()) {
                    steps.add(new SandboxResult.StepResult(name, SandboxResult.TIMEOUT, execution.exitCode(), duration,
                            stepTail, 0, 0));
                    continue;
                }
                var statusFile = packageDir.resolve("status").resolve(target.id());
                if (Files.exists(statusFile, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(statusFile, LinkOption.NOFOLLOW_LINKS)) {
                    steps.add(new SandboxResult.StepResult(name, SandboxResult.FAIL, execution.exitCode(), duration,
                            "estado inválido", 0, 0));
                    continue;
                }
                var content = Files.exists(statusFile, LinkOption.NOFOLLOW_LINKS)
                        ? Files.readString(statusFile).strip() : null;
                if ("PASS".equals(content)) {
                    steps.add(new SandboxResult.StepResult(name, SandboxResult.PASS, execution.exitCode(), duration,
                            stepTail, 0, 0));
                } else {
                    var reason = content == null ? stepTail
                            : content.startsWith("FAIL:") ? content.substring(5).strip() : content;
                    steps.add(new SandboxResult.StepResult(name, SandboxResult.FAIL, execution.exitCode(), duration,
                            tail(reason, OUTPUT_TAIL_CHARS), 0, 0));
                }
            }

            deleteQuietly(packageDir.resolve("status"));
            deleteQuietly(jobDir.resolve(".forjai/keystore"));

            var destination = stagingRoot.resolve(missionId).resolve("incremento-" + increment).normalize();
            if (!destination.startsWith(stagingRoot)) {
                throw new IOException("missionId inválido para el staging.");
            }
            deleteQuietly(destination);
            Files.createDirectories(destination);
            if (Files.isDirectory(packageDir, LinkOption.NOFOLLOW_LINKS)) {
                copyTree(packageDir, destination);
            }

            var overall = steps.stream().allMatch(s -> SandboxResult.PASS.equals(s.status()))
                    ? SandboxResult.PASS : SandboxResult.FAIL;
            return new SandboxResult(overall, steps);

        } catch (IOException | IllegalArgumentException ex) {
            steps.add(new SandboxResult.StepResult("package", SandboxResult.FAIL, -1, 0,
                    "No se pudo empaquetar: " + ex.getMessage(), 0, 0));
            return new SandboxResult(SandboxResult.FAIL, steps);
        } finally {
            deleteQuietly(jobDir);
            try {
                Files.deleteIfExists(envFile);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    private static SandboxResult.StepResult skipped(String name) {
        return new SandboxResult.StepResult(name, SandboxResult.SKIPPED, 0, 0, "Omitido: un paso anterior falló.", 0, 0);
    }

    /** Copia solo directorios y archivos regulares: la salida la escribió código generado, así que un symlink se omite. */
    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean unsafeEnvValue(String value) {
        return value == null || value.contains("\n") || value.contains("\r") || value.contains("\0");
    }

    private SandboxResult.StepResult checkout(String missionId, String commitSha, String componentDir, Path jobDir) {

        var started = System.currentTimeMillis();
        var repo = productsRoot.resolve(missionId).normalize();

        var dir = componentDir == null ? "" : componentDir;
        if (!repo.startsWith(productsRoot) || !commitSha.matches("[0-9a-f]{40}") || !COMPONENT_DIRS.contains(dir)) {
            return new SandboxResult.StepResult("checkout", SandboxResult.FAIL, -1, 0,
                    "missionId, commitSha o componentDir inválidos.", 0, 0);
        }
        var treeish = dir.isEmpty() ? commitSha : commitSha + ":" + dir;

        var tar = jobDir.resolve(".forjai-src.tar");
        var archive = executor.run(List.of("git", "-c", "safe.directory=*", "-C", repo.toString(), "archive",
                "--format=tar", "-o", tar.toString(), treeish), jobDir, 60);
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

        // Verificado en vivo (Pong, 2026-10-15): el paso y el motivo de cada partida fallida van al final de la salida,
        // que es lo que la corrección le muestra al equipo.
        var output = (execution.output() == null ? "" : execution.output()) + TestReportParser.failureDetails(counts.tests());
        return new SandboxResult.StepResult(step.name(), status, execution.exitCode(), duration,
                tail(output, OUTPUT_TAIL_CHARS), counts.passed(), counts.failed(), counts.tests());
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

package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FETCH_DEPENDENCIES (spec §3, revisión 2026-09-27): proxy squid con lista blanca en la red Podman por defecto y en
 * la red interna; el contenedor de restauración solo ve la red interna. Nunca compila ni ejecuta los paquetes.
 */
public class DependencyFetcher {

    static final int RESTORE_TIMEOUT_SECONDS = 600;
    static final int OUTPUT_TAIL_CHARS = 20_000;

    private final Path depsRoot;
    private final String podmanUrl;
    private final String network;
    private final String proxyImage;
    private final ProcessExecutor executor;
    private final int readinessAttempts;
    private final java.time.Duration readinessDelay;

    public DependencyFetcher(Path depsRoot, String podmanUrl, String network, String proxyImage, ProcessExecutor executor) {
        this(depsRoot, podmanUrl, network, proxyImage, executor, 30, java.time.Duration.ofSeconds(1));
    }

    public DependencyFetcher(Path depsRoot, String podmanUrl, String network, String proxyImage, ProcessExecutor executor,
                             int readinessAttempts, java.time.Duration readinessDelay) {
        this.depsRoot = depsRoot;
        this.podmanUrl = podmanUrl;
        this.network = network;
        this.proxyImage = proxyImage;
        this.executor = executor;
        this.readinessAttempts = readinessAttempts;
        this.readinessDelay = readinessDelay;
    }

    public FetchedPackage.FetchResult fetch(String ecosystem, List<DependencyRequest.Package> packages) {

        var jobId = "fetch-" + UUID.randomUUID();
        if (packages == null || packages.isEmpty() || !packages.stream().allMatch(DependencyRequest::valid)
                || !List.of("NUGET", "PUB").contains(ecosystem)) {
            return new FetchedPackage.FetchResult(jobId, "FAIL", "Pedido inválido: ecosistema, nombres o versiones exactas.", List.of());
        }

        var jobDir = depsRoot.resolve("staging").resolve(jobId);
        var proxy = "forjai-egress-" + jobId.substring(6, 14);
        var out = new StringBuilder();

        try {
            Files.createDirectories(jobDir.resolve("proj"));
            Files.createDirectories(jobDir.resolve("packages"));
            writeProject(ecosystem, packages, jobDir.resolve("proj"));

            if (podman(List.of("network", "exists", network), out).exitCode() != 0) {
                podman(List.of("network", "create", "--internal", network), out);
            }

            var started = podman(List.of("run", "-d", "--rm", "--name", proxy, "--network", "podman",
                    "--cap-drop=ALL", "--cap-add=SETUID", "--cap-add=SETGID", proxyImage), out);
            if (started.exitCode() != 0) {
                return new FetchedPackage.FetchResult(jobId, "FAIL", tail("El proxy de salida no arrancó: " + out), List.of());
            }
            podman(List.of("network", "connect", network, proxy), out);

            // Verificado en vivo (MISSION-DEPS-VERIFY-2): la restauración arrancaba antes de que squid escuchara.
            if (!proxyReady(proxy)) {
                return new FetchedPackage.FetchResult(jobId, "FAIL",
                        tail("El proxy de salida no quedó listo a tiempo: " + out), List.of());
            }

            var restore = executor.run(restoreCommand(ecosystem, jobDir, proxy), jobDir, RESTORE_TIMEOUT_SECONDS);
            out.append(restore.output());
            if (restore.timedOut() || restore.exitCode() != 0) {
                var proxyLogs = executor.run(List.of("podman", "--url", podmanUrl, "logs", proxy), depsRoot, 30);
                out.append("\n--- logs del proxy ---\n").append(proxyLogs.output());
                return new FetchedPackage.FetchResult(jobId, restore.timedOut() ? "TIMEOUT" : "FAIL", tail(out.toString()), List.of());
            }

            var metadata = "NUGET".equals(ecosystem)
                    ? PackageMetadataReader.nuget(jobDir.resolve("packages"))
                    : PackageMetadataReader.pub(jobDir.resolve("packages"));
            return new FetchedPackage.FetchResult(jobId, "PASS", tail(out.toString()), metadata);

        } catch (IOException ex) {
            return new FetchedPackage.FetchResult(jobId, "FAIL", "No se pudo preparar el staging: " + ex.getMessage(), List.of());
        } finally {
            // Review Focus: el proxy nunca queda vivo.
            executor.run(List.of("podman", "--url", podmanUrl, "rm", "-f", proxy), depsRoot, 60);
        }
    }

    private List<String> restoreCommand(String ecosystem, Path jobDir, String proxy) {
        var proxyUrl = "http://" + proxy + ":3128";
        var args = new ArrayList<>(List.of("podman", "--url", podmanUrl, "run", "--rm",
                "--network=" + network, "--cap-drop=ALL", "--security-opt=no-new-privileges", "--userns=keep-id",
                "--memory=4g", "--cpus=4", "--pids-limit=512", "--timeout=" + RESTORE_TIMEOUT_SECONDS,
                "-e", "HOME=/tmp", "-e", "DOTNET_CLI_TELEMETRY_OPTOUT=1",
                "-e", "HTTPS_PROXY=" + proxyUrl, "-e", "HTTP_PROXY=" + proxyUrl, "-e", "https_proxy=" + proxyUrl,
                "-v", jobDir + ":/fetch:Z", "-w", "/fetch/proj"));
        if ("NUGET".equals(ecosystem)) {
            args.addAll(List.of("localhost/forjai-sandbox/dotnet-app:1", "dotnet", "restore", "Fetch.csproj",
                    "--packages", "/fetch/packages", "--source", "https://api.nuget.org/v3/index.json"));
        } else {
            args.addAll(List.of("-e", "PUB_CACHE=/fetch/packages", "localhost/forjai-sandbox/flutter-web-app:1",
                    "flutter", "--no-version-check", "pub", "get"));
        }
        return args;
    }

    private static void writeProject(String ecosystem, List<DependencyRequest.Package> packages, Path dir) throws IOException {
        if ("NUGET".equals(ecosystem)) {
            var refs = new StringBuilder();
            packages.forEach(p -> refs.append("<PackageReference Include=\"").append(p.name())
                    .append("\" Version=\"").append(p.version()).append("\" />"));
            Files.writeString(dir.resolve("Fetch.csproj"), "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>"
                    + "<TargetFramework>net8.0</TargetFramework></PropertyGroup><ItemGroup>" + refs + "</ItemGroup></Project>");
        } else {
            var deps = new StringBuilder();
            packages.forEach(p -> deps.append("  ").append(p.name()).append(": ").append(p.version()).append("\n"));
            Files.writeString(dir.resolve("pubspec.yaml"), "name: fetch\nenvironment:\n  sdk: ^3.5.0\n"
                    + "dependencies:\n  flutter:\n    sdk: flutter\n" + deps);
        }
    }

    private boolean proxyReady(String proxy) {
        for (int attempt = 0; attempt < readinessAttempts; attempt++) {
            var logs = executor.run(List.of("podman", "--url", podmanUrl, "logs", proxy), depsRoot, 30);
            if (logs.output() != null && logs.output().contains("Accepting HTTP Socket connections")) {
                return true;
            }
            try {
                Thread.sleep(readinessDelay.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private ProcessExecutor.Execution podman(List<String> args, StringBuilder out) {
        var command = new ArrayList<>(List.of("podman", "--url", podmanUrl));
        command.addAll(args);
        var execution = executor.run(command, depsRoot, 120);
        out.append(execution.output()).append('\n');
        return execution;
    }

    private static String tail(String output) {
        return output.length() <= OUTPUT_TAIL_CHARS ? output : output.substring(output.length() - OUTPUT_TAIL_CHARS);
    }
}

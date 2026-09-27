# Sandbox de verificación — Parte 3: dependencias gobernadas — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Engineering puede usar paquetes NuGet y pub que no vienen en las imágenes: se descargan aislados por un proxy con lista blanca, pasan chequeos deterministas (vulnerabilidades, licencia, código en el build) y entran a una caché aprobada que `VERIFY` usa sin red; lo que no pasa queda pendiente del fundador (🔴).

**Architecture:** `company-core` detecta los paquetes pedidos (campo `packages` de `DevelopmentResult` en .NET, `pubspec.yaml` en Flutter; solo versiones exactas), descarta los ya aprobados o de la imagen, y pide al `sandbox-runner` un job `FETCH_DEPENDENCIES`: un contenedor en una red Podman interna cuya única salida es un proxy squid con lista blanca restaura un proyecto mínimo a `~/forjai-deps/staging/<job>` y devuelve licencia y "código en el build" de cada paquete (incluidos transitivos). `company-core` consulta OSV, aplica la política, persiste `(:Dependency)` y pide `PROMOTE_DEPENDENCIES` para lo aprobado. `VERIFY` monta la caché aprobada (NuGet como `fallbackPackageFolders` de solo lectura; pub como `PUB_CACHE` con montaje overlay `:O`).

**Tech Stack:** Java 21 / Spring Boot 4.1.1 (ambos módulos), Podman 5 sin root, squid (alpine), .NET SDK 8, Flutter 3.24, API de OSV (`api.osv.dev`), Neo4j.

**Spec:** `docs/superpowers/specs/2026-09-26-sandbox-verification-design.md` §3 y "Revisión 2026-09-27 (parte 3)".

## Global Constraints

- Rama `dependencias-gobernadas`. Commits con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Lista blanca del proxy, exacta: `api.nuget.org`, `pub.dev`, `storage.googleapis.com`; solo `CONNECT` al puerto 443. OSV lo consulta `company-core`, no el proxy.
- `FETCH_DEPENDENCIES` solo corre la restauración del gestor sobre un proyecto mínimo generado por el runner; nunca compila ni ejecuta código de los paquetes. `VERIFY` sigue siempre con `--network=none`.
- Solo versiones exactas (`^\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?$`); nombres `^[A-Za-z0-9_.-]{1,100}$`. Rango, `^`, `~`, `*`, `latest`, `path:`, `git:` → error corregible (reintento).
- Licencias permitidas: `MIT`, `Apache-2.0`, `BSD-2-Clause`, `BSD-3-Clause`, `ISC`, `Zlib`. NuGet: expresión del `.nuspec` (`OR` → alguna permitida; `AND` → todas); solo `licenseUrl` → no reconocida. Pub: firma del texto de `LICENSE`; no reconocida → no permitida.
- Código en el build: NuGet con archivos en `build/`, `buildTransitive/` o `tools/*.ps1`; pub con carpeta `hook/`.
- Política: `APPROVED` (`approvedBy='policy'`) si no hay vulnerabilidades HIGH/CRITICAL (severidad desconocida cuenta como bloqueante), licencia permitida y sin código en el build; si no, `PENDING_APPROVAL` (🔴) con los motivos exactos. Baseline de las imágenes: `approvedBy='baseline'`.
- Paquetes pendientes → `VERIFY` no corre (motivo en el sandbox) y la misión queda `UNVALIDATED`.
- Caché: `~/forjai-deps/{staging,nuget,pub}` (pub sembrada una vez con `/opt/pub-cache` de la imagen Flutter).
- Suites en verde en cada tarea: `company-core` (baseline 441) y `sandbox-runner` (baseline 18).

## Review Focus

- El proxy no arranca o la red interna no existe: el job debe devolver `FAIL` con el motivo, nunca colgarse ni dejar un contenedor de proxy vivo — test en Task 1.
- Un paquete ya aprobado vuelve a pedirse en otra misión: no se descarga ni se re-evalúa — test en Task 6.
- OSV no responde: el paquete queda `PENDING_APPROVAL` con "no se pudo consultar OSV", nunca aprobado a ciegas — test en Task 5.
- Aprobar a mano un paquete cuyo staging ya no existe: el endpoint devuelve el error del runner y el estado no cambia — test en Task 7.
- `pubspec.yaml` con una dependencia `sdk: flutter` o `path:`: la primera se ignora, la segunda es error corregible — test en Task 3.

---

### Task 1: Runner — proxy con lista blanca y job `FETCH_DEPENDENCIES`

**Files:**
- Create: `sandbox/images/egress-proxy/Dockerfile`, `sandbox/images/egress-proxy/squid.conf`
- Modify: `sandbox/build-images.sh` (construye `egress-proxy`)
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/DependencyRequest.java`
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/FetchedPackage.java`
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/PackageMetadataReader.java`
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/DependencyFetcher.java`
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/PackageMetadataReaderTest.java`
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/DependencyFetcherTest.java`

**Interfaces:**
- Produces: `record DependencyRequest.Package(String name, String version)`; `record FetchedPackage(String name, String version, String licenseExpression, String licenseText, boolean buildCode, List<String> buildCodeFiles)`; `record FetchResult(String jobId, String status, String outputTail, List<FetchedPackage> packages)`; `DependencyFetcher.fetch(String ecosystem, List<DependencyRequest.Package> packages) → FetchResult` (`ecosystem` ∈ `NUGET|PUB`); `PackageMetadataReader.nuget(Path stagingDir)` y `.pub(Path stagingDir)` → `List<FetchedPackage>`.

- [ ] **Step 1: Imagen del proxy** (verificado en la prueba del 2026-09-27: squid rechaza `api.nuget.org` junto a `.nuget.org`)

`sandbox/images/egress-proxy/squid.conf`:
```
http_port 3128
acl allowed dstdomain api.nuget.org pub.dev storage.googleapis.com
acl CONNECT method CONNECT
acl SSL_ports port 443
http_access deny CONNECT !SSL_ports
http_access allow allowed
http_access deny all
cache deny all
access_log stdio:/dev/stdout
cache_log stdio:/dev/stderr
pid_filename none
```
`sandbox/images/egress-proxy/Dockerfile`:
```dockerfile
FROM docker.io/library/alpine:3.20
RUN apk add --no-cache squid
COPY squid.conf /etc/squid/squid.conf
EXPOSE 3128
CMD ["squid", "-N", "-f", "/etc/squid/squid.conf"]
```
En `sandbox/build-images.sh`, agregar `egress-proxy` a la lista de perfiles construidos.

- [ ] **Step 2: Tests (fallan)**

```java
package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackageMetadataReaderTest {

    @TempDir
    Path staging;

    // Estructura real verificada: <id>/<version>/<id>.nuspec con <license type="expression">.
    @Test
    void readsNugetLicenseExpressionAndBuildCode() throws Exception {
        var dir = Files.createDirectories(staging.resolve("newtonsoft.json/13.0.3"));
        Files.writeString(dir.resolve("newtonsoft.json.nuspec"), """
                <package><metadata><id>Newtonsoft.Json</id><version>13.0.3</version>
                <license type="expression">MIT</license></metadata></package>""");
        var withBuild = Files.createDirectories(staging.resolve("evil.pkg/1.0.0/build"));
        Files.writeString(withBuild.resolve("evil.pkg.targets"), "<Project/>");
        Files.writeString(staging.resolve("evil.pkg/1.0.0/evil.pkg.nuspec"),
                "<package><metadata><id>Evil.Pkg</id><version>1.0.0</version><licenseUrl>http://x</licenseUrl></metadata></package>");

        var packages = PackageMetadataReader.nuget(staging);

        var json = packages.stream().filter(p -> p.name().equals("Newtonsoft.Json")).findFirst().orElseThrow();
        assertEquals("13.0.3", json.version());
        assertEquals("MIT", json.licenseExpression());
        assertFalse(json.buildCode());
        var evil = packages.stream().filter(p -> p.name().equals("Evil.Pkg")).findFirst().orElseThrow();
        assertNull(evil.licenseExpression());
        assertTrue(evil.buildCode());
        assertEquals(List.of("build/evil.pkg.targets"), evil.buildCodeFiles());
    }

    // Estructura real verificada: hosted/pub.dev/<name>-<version>/LICENSE.
    @Test
    void readsPubLicenseTextAndHooks() throws Exception {
        var pkg = Files.createDirectories(staging.resolve("hosted/pub.dev/equatable-2.0.5"));
        Files.writeString(pkg.resolve("LICENSE"), "MIT License\n\nPermission is hereby granted, free of charge");
        Files.createDirectories(staging.resolve("hosted/pub.dev/native_thing-0.1.0/hook"));

        var packages = PackageMetadataReader.pub(staging);

        var equatable = packages.stream().filter(p -> p.name().equals("equatable")).findFirst().orElseThrow();
        assertEquals("2.0.5", equatable.version());
        assertTrue(equatable.licenseText().startsWith("MIT License"));
        assertFalse(equatable.buildCode());
        assertTrue(packages.stream().filter(p -> p.name().equals("native_thing")).findFirst().orElseThrow().buildCode());
    }
}
```

```java
package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyFetcherTest {

    @TempDir
    Path deps;

    private final List<List<String>> calls = new ArrayList<>();

    private DependencyFetcher fetcher(ProcessExecutor executor) {
        return new DependencyFetcher(deps, "unix:///s", "forjai-fetch", "localhost/forjai-sandbox/egress-proxy:1", executor);
    }

    @Test
    void theRestoreRunsOnTheInternalNetworkThroughTheProxyAndTheProxyIsAlwaysRemoved() {
        var result = fetcher((command, dir, timeout) -> {
            calls.add(command);
            return new ProcessExecutor.Execution(0, "ok", false);
        }).fetch("NUGET", List.of(new DependencyRequest.Package("Newtonsoft.Json", "13.0.3")));

        assertEquals("PASS", result.status());
        var restore = calls.stream().filter(c -> c.contains("dotnet")).findFirst().orElseThrow();
        assertTrue(restore.contains("--network=forjai-fetch"), restore.toString());
        assertTrue(restore.stream().anyMatch(a -> a.startsWith("HTTPS_PROXY=http://forjai-egress-")), restore.toString());
        assertTrue(calls.stream().anyMatch(c -> c.containsAll(List.of("rm", "-f")) && c.stream().anyMatch(a -> a.startsWith("forjai-egress-"))));
    }

    // Review Focus: el proxy no arranca → FAIL con motivo, sin restore y sin proxy colgado.
    @Test
    void aProxyThatDoesNotStartFailsTheJob() {
        var result = fetcher((command, dir, timeout) -> {
            calls.add(command);
            var starting = command.contains("-d");
            return new ProcessExecutor.Execution(starting ? 125 : 0, starting ? "no such image" : "", false);
        }).fetch("PUB", List.of(new DependencyRequest.Package("equatable", "2.0.5")));

        assertEquals("FAIL", result.status());
        assertTrue(result.outputTail().contains("proxy"), result.outputTail());
        assertTrue(calls.stream().noneMatch(c -> c.contains("flutter")));
        assertTrue(calls.stream().anyMatch(c -> c.containsAll(List.of("rm", "-f"))));
    }

    @Test
    void invalidNamesOrVersionsAreRejectedBeforeRunningAnything() {
        var result = fetcher((command, dir, timeout) -> {
            calls.add(command);
            return new ProcessExecutor.Execution(0, "", false);
        }).fetch("NUGET", List.of(new DependencyRequest.Package("x;rm -rf /", "^1.0.0")));

        assertEquals("FAIL", result.status());
        assertTrue(calls.isEmpty());
    }
}
```

- [ ] **Step 3: Correr** — `cd sandbox-runner && mvn -q test` → FAIL de compilación.

- [ ] **Step 4: Implementar**

`DependencyRequest.java`:
```java
package com.forjai.sandbox;

import java.util.List;
import java.util.regex.Pattern;

/** Pedido de FETCH_DEPENDENCIES / PROMOTE_DEPENDENCIES. Solo nombres y versiones exactas (spec §3). */
public record DependencyRequest(String ecosystem, String jobId, List<Package> packages) {

    public record Package(String name, String version) {
    }

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    static final Pattern VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$");

    static boolean valid(Package p) {
        return p != null && p.name() != null && p.version() != null
                && NAME.matcher(p.name()).matches() && VERSION.matcher(p.version()).matches();
    }
}
```

`FetchedPackage.java`:
```java
package com.forjai.sandbox;

import java.util.List;

public record FetchedPackage(String name, String version, String licenseExpression, String licenseText,
                             boolean buildCode, List<String> buildCodeFiles) {

    public record FetchResult(String jobId, String status, String outputTail, List<FetchedPackage> packages) {
    }
}
```

`PackageMetadataReader.java`:
```java
package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Metadatos de lo restaurado en staging (estructuras verificadas en vivo el 2026-09-27). */
public final class PackageMetadataReader {

    private static final Pattern ID = Pattern.compile("<id>([^<]+)</id>");
    private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern LICENSE = Pattern.compile("<license\\s+type=\"expression\"\\s*>([^<]+)</license>");
    private static final int LICENSE_TEXT_CHARS = 4000;

    private PackageMetadataReader() {
    }

    public static List<FetchedPackage> nuget(Path staging) {
        var result = new ArrayList<FetchedPackage>();
        try (var ids = list(staging)) {
            for (var idDir : ids.filter(Files::isDirectory).toList()) {
                try (var versions = list(idDir)) {
                    for (var versionDir : versions.filter(Files::isDirectory).toList()) {
                        result.add(nugetPackage(versionDir));
                    }
                }
            }
        }
        return result;
    }

    private static FetchedPackage nugetPackage(Path dir) {
        var nuspec = firstFile(dir, ".nuspec");
        var text = nuspec == null ? "" : read(nuspec, Integer.MAX_VALUE);
        var id = match(ID, text, dir.getParent().getFileName().toString());
        var version = match(VERSION, text, dir.getFileName().toString());
        var license = match(LICENSE, text, null);
        var buildFiles = new ArrayList<String>();
        for (var folder : List.of("build", "buildTransitive")) {
            buildFiles.addAll(relativeFiles(dir, dir.resolve(folder), f -> true));
        }
        buildFiles.addAll(relativeFiles(dir, dir.resolve("tools"), f -> f.toString().endsWith(".ps1")));
        return new FetchedPackage(id, version, license == null ? null : license.strip(), null,
                !buildFiles.isEmpty(), buildFiles);
    }

    public static List<FetchedPackage> pub(Path staging) {
        var result = new ArrayList<FetchedPackage>();
        try (var packages = list(staging.resolve("hosted/pub.dev"))) {
            for (var dir : packages.filter(Files::isDirectory).toList()) {
                var folder = dir.getFileName().toString();
                var dash = folder.lastIndexOf('-');
                if (dash <= 0) {
                    continue;
                }
                var license = Files.isRegularFile(dir.resolve("LICENSE")) ? read(dir.resolve("LICENSE"), LICENSE_TEXT_CHARS) : null;
                var hook = Files.isDirectory(dir.resolve("hook"));
                result.add(new FetchedPackage(folder.substring(0, dash), folder.substring(dash + 1), null, license,
                        hook, hook ? List.of("hook/") : List.of()));
            }
        }
        return result;
    }

    private static Stream<Path> list(Path dir) {
        try {
            return Files.isDirectory(dir) ? Files.list(dir) : Stream.empty();
        } catch (IOException ex) {
            return Stream.empty();
        }
    }

    private static Path firstFile(Path dir, String suffix) {
        try (var files = list(dir)) {
            return files.filter(f -> f.toString().endsWith(suffix)).findFirst().orElse(null);
        }
    }

    private static List<String> relativeFiles(Path root, Path dir, java.util.function.Predicate<Path> filter) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).filter(filter)
                    .map(f -> root.relativize(f).toString().replace('\\', '/')).sorted().toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private static String read(Path file, int maxChars) {
        try {
            var text = Files.readString(file);
            return text.length() <= maxChars ? text : text.substring(0, maxChars);
        } catch (IOException | RuntimeException ex) {
            return "";
        }
    }

    private static String match(Pattern pattern, String text, String fallback) {
        var m = pattern.matcher(text);
        return m.find() ? m.group(1) : fallback;
    }
}
```

`DependencyFetcher.java`:
```java
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

    public DependencyFetcher(Path depsRoot, String podmanUrl, String network, String proxyImage, ProcessExecutor executor) {
        this.depsRoot = depsRoot;
        this.podmanUrl = podmanUrl;
        this.network = network;
        this.proxyImage = proxyImage;
        this.executor = executor;
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

            var restore = executor.run(restoreCommand(ecosystem, jobDir, proxy), jobDir, RESTORE_TIMEOUT_SECONDS);
            out.append(restore.output());
            if (restore.timedOut() || restore.exitCode() != 0) {
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
```

- [ ] **Step 5: Correr** — `cd sandbox-runner && mvn -q test` → PASS (18 + 5).

- [ ] **Step 6: Commit** — `git add sandbox sandbox-runner && git commit -m "sandbox-runner: proxy con lista blanca y FETCH_DEPENDENCIES con metadatos de licencia y código en el build"`

---

### Task 2: Runner — `PROMOTE_DEPENDENCIES`, API y caché aprobada en `VERIFY`

**Files:**
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/DependencyPromoter.java`
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/JobController.java` (jobTypes nuevos)
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/PodmanCommandBuilder.java` (montajes de caché)
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/RunnerConfig.java`, `src/main/resources/application.yml`
- Modify: `sandbox/images/dotnet-app/NuGet.Config`, `sandbox/images/godot-dotnet-game/NuGet.Config`, ambos `run.sh`
- Modify: `sandbox/build-images.sh` (siembra `~/forjai-deps/pub`), `docker-compose.yml` (monta `~/forjai-deps`)
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/DependencyPromoterTest.java`, `PodmanCommandBuilderTest.java`, `JobControllerTest.java`

**Interfaces:**
- Consumes: `DependencyFetcher.fetch`, `DependencyRequest` (Task 1).
- Produces: `DependencyPromoter.promote(String ecosystem, String jobId, List<DependencyRequest.Package>) → List<String>` (paquetes promovidos; lanza `IllegalStateException` si falta el staging); `JobController` acepta `jobType` `FETCH_DEPENDENCIES` y `PROMOTE_DEPENDENCIES` (body `DependencyRequest` en el campo `dependencies`); `PodmanCommandBuilder(String podmanUrl, Path depsRoot)`.

- [ ] **Step 1: Tests (fallan)**

```java
package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyPromoterTest {

    @TempDir
    Path deps;

    @Test
    void promotesNugetFromStagingToTheApprovedCache() throws Exception {
        var staged = Files.createDirectories(deps.resolve("staging/fetch-1/packages/newtonsoft.json/13.0.3"));
        Files.writeString(staged.resolve("newtonsoft.json.13.0.3.nupkg"), "x");

        var promoted = new DependencyPromoter(deps).promote("NUGET", "fetch-1",
                List.of(new DependencyRequest.Package("Newtonsoft.Json", "13.0.3")));

        assertEquals(List.of("Newtonsoft.Json@13.0.3"), promoted);
        assertTrue(Files.isRegularFile(deps.resolve("nuget/newtonsoft.json/13.0.3/newtonsoft.json.13.0.3.nupkg")));
    }

    @Test
    void promotesPubPackageAndItsHash() throws Exception {
        Files.createDirectories(deps.resolve("staging/fetch-2/packages/hosted/pub.dev/equatable-2.0.5/lib"));
        Files.createDirectories(deps.resolve("staging/fetch-2/packages/hosted-hashes/pub.dev"));
        Files.writeString(deps.resolve("staging/fetch-2/packages/hosted-hashes/pub.dev/equatable-2.0.5.sha256"), "h");

        new DependencyPromoter(deps).promote("PUB", "fetch-2", List.of(new DependencyRequest.Package("equatable", "2.0.5")));

        assertTrue(Files.isDirectory(deps.resolve("pub/hosted/pub.dev/equatable-2.0.5/lib")));
        assertTrue(Files.isRegularFile(deps.resolve("pub/hosted-hashes/pub.dev/equatable-2.0.5.sha256")));
    }

    // Review Focus: staging inexistente → error explícito, nada se promueve.
    @Test
    void aMissingStagingFails() {
        assertThrows(IllegalStateException.class, () -> new DependencyPromoter(deps).promote("NUGET", "fetch-x",
                List.of(new DependencyRequest.Package("A", "1.0.0"))));
    }

    @Test
    void aJobIdWithPathTraversalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DependencyPromoter(deps).promote("NUGET", "../nuget",
                List.of(new DependencyRequest.Package("A", "1.0.0"))));
    }
}
```

En `PodmanCommandBuilderTest` agregar (y actualizar el constructor de los tests existentes a `new PodmanCommandBuilder("unix:///run/podman/podman.sock", Path.of("/home/u/forjai-deps"))`):
```java
    // Parte 3: la caché aprobada entra a VERIFY sin red. NuGet de solo lectura; pub con overlay (:O), verificado
    // en vivo: pub get --offline escribe en su caché y falla con :ro.
    @Test
    void verifyMountsTheApprovedDependencyCaches() {
        var dotnet = builder.run(ExecutionProfile.DOTNET_APP, ExecutionProfile.DOTNET_APP.steps().get(0), Path.of("/w"));
        assertTrue(dotnet.contains("/home/u/forjai-deps/nuget:/deps/nuget:ro"), dotnet.toString());
        var flutter = builder.run(ExecutionProfile.FLUTTER_WEB_APP, ExecutionProfile.FLUTTER_WEB_APP.steps().get(0), Path.of("/w"));
        assertTrue(flutter.contains("/home/u/forjai-deps/pub:/opt/pub-cache:O"), flutter.toString());
        assertTrue(flutter.contains("--network=none"));
    }
```

En `JobControllerTest` agregar:
```java
    @Test
    void fetchAndPromoteAreRoutedWithTheToken() {
        var fetcher = mock(DependencyFetcher.class);
        var promoter = mock(DependencyPromoter.class);
        var c = new JobController(runner, fetcher, promoter, "secreto");
        var pkgs = List.of(new DependencyRequest.Package("A", "1.0.0"));
        when(fetcher.fetch("NUGET", pkgs)).thenReturn(new FetchedPackage.FetchResult("fetch-1", "PASS", "", List.of()));

        assertEquals(200, c.run("secreto", new JobController.JobRequest("FETCH_DEPENDENCIES", null, null, null,
                new DependencyRequest("NUGET", null, pkgs))).getStatusCode().value());
        assertEquals(401, c.run("otro", new JobController.JobRequest("PROMOTE_DEPENDENCIES", null, null, null,
                new DependencyRequest("NUGET", "fetch-1", pkgs))).getStatusCode().value());
        verifyNoInteractions(promoter);
    }
```
(Actualizar los tests existentes de `JobControllerTest` al constructor nuevo `new JobController(runner, mock(DependencyFetcher.class), mock(DependencyPromoter.class), "secreto")` y a `JobRequest` con quinto componente `null`.)

- [ ] **Step 2: Correr** — `cd sandbox-runner && mvn -q test` → FAIL.

- [ ] **Step 3: Implementar**

`DependencyPromoter.java`:
```java
package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** PROMOTE_DEPENDENCIES: staging → caché aprobada (~/forjai-deps/nuget | pub). Solo copia archivos. */
public class DependencyPromoter {

    private static final Pattern JOB_ID = Pattern.compile("^fetch-[0-9a-f-]{1,40}$|^fetch-\\d+$");

    private final Path depsRoot;

    public DependencyPromoter(Path depsRoot) {
        this.depsRoot = depsRoot;
    }

    public List<String> promote(String ecosystem, String jobId, List<DependencyRequest.Package> packages) {
        if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
            throw new IllegalArgumentException("jobId inválido: " + jobId);
        }
        var staged = depsRoot.resolve("staging").resolve(jobId).resolve("packages");
        if (!Files.isDirectory(staged)) {
            throw new IllegalStateException("No existe el staging del job " + jobId + ".");
        }
        var promoted = new ArrayList<String>();
        for (var p : packages) {
            if (!DependencyRequest.valid(p)) {
                throw new IllegalArgumentException("Paquete inválido: " + p);
            }
            try {
                if ("NUGET".equals(ecosystem)) {
                    var rel = Path.of(p.name().toLowerCase(Locale.ROOT), p.version().toLowerCase(Locale.ROOT));
                    copyTree(staged.resolve(rel), depsRoot.resolve("nuget").resolve(rel));
                } else if ("PUB".equals(ecosystem)) {
                    var folder = p.name() + "-" + p.version();
                    copyTree(staged.resolve("hosted/pub.dev").resolve(folder), depsRoot.resolve("pub/hosted/pub.dev").resolve(folder));
                    var hash = staged.resolve("hosted-hashes/pub.dev").resolve(folder + ".sha256");
                    if (Files.isRegularFile(hash)) {
                        var target = depsRoot.resolve("pub/hosted-hashes/pub.dev").resolve(folder + ".sha256");
                        Files.createDirectories(target.getParent());
                        Files.copy(hash, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    throw new IllegalArgumentException("Ecosistema inválido: " + ecosystem);
                }
                promoted.add(p.name() + "@" + p.version());
            } catch (IOException ex) {
                throw new IllegalStateException("No se pudo promover " + p.name() + "@" + p.version() + ": " + ex.getMessage(), ex);
            }
        }
        return promoted;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("no está en el staging: " + source.getFileName());
        }
        try (var walk = Files.walk(source)) {
            for (var path : walk.toList()) {
                var dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
```

`JobController`: el `record JobRequest` gana `DependencyRequest dependencies` como quinto componente; el constructor recibe `DependencyFetcher` y `DependencyPromoter`; después del chequeo de token:
```java
        if ("FETCH_DEPENDENCIES".equals(request.jobType()) && request.dependencies() != null) {
            return ResponseEntity.ok(fetcher.fetch(request.dependencies().ecosystem(), request.dependencies().packages()));
        }
        if ("PROMOTE_DEPENDENCIES".equals(request.jobType()) && request.dependencies() != null) {
            try {
                return ResponseEntity.ok(Map.of("promoted", promoter.promote(request.dependencies().ecosystem(),
                        request.dependencies().jobId(), request.dependencies().packages())));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
            }
        }
```

`PodmanCommandBuilder`: constructor `(String podmanUrl, Path depsRoot)`; en `run(...)`, antes de la imagen:
```java
        if (profile == ExecutionProfile.FLUTTER_WEB_APP) {
            args.addAll(List.of("-v", depsRoot.resolve("pub") + ":/opt/pub-cache:O"));
        } else {
            args.addAll(List.of("-v", depsRoot.resolve("nuget") + ":/deps/nuget:ro"));
        }
```

`RunnerConfig`: beans `DependencyFetcher` (`sandbox.deps-root`, `sandbox.podman-url`, `sandbox.fetch-network` default `forjai-fetch`, `sandbox.proxy-image` default `localhost/forjai-sandbox/egress-proxy:1`) y `DependencyPromoter`; `PodmanCommandBuilder` recibe `deps-root`. `application.yml`: `deps-root: ${SANDBOX_DEPS_ROOT:${user.home}/forjai-deps}`.

`NuGet.Config` (ambas imágenes), verificado en vivo con `--read-only`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<configuration>
  <packageSources>
    <clear />
    <add key="forjai-baseline" value="/opt/nuget-packages" />
    <add key="forjai-approved" value="/deps/nuget" />
  </packageSources>
  <config><add key="globalPackagesFolder" value="/tmp/nuget-packages" /></config>
  <fallbackPackageFolders>
    <add key="forjai-baseline" value="/opt/nuget-packages" />
    <add key="forjai-approved" value="/deps/nuget" />
  </fallbackPackageFolders>
</configuration>
```
En ambos `run.sh`: quitar `export NUGET_PACKAGES=/opt/nuget-packages` y el `--source /opt/nuget-packages` del restore (las fuentes salen del `NuGet.Config`).

`build-images.sh`, al final:
```bash
mkdir -p ~/forjai-deps/staging ~/forjai-deps/nuget
if [ ! -d ~/forjai-deps/pub/hosted ]; then
  mkdir -p ~/forjai-deps/pub
  podman run --rm --userns=keep-id -v ~/forjai-deps/pub:/seed:Z localhost/forjai-sandbox/flutter-web-app:1 \
    bash -c 'cp -a /opt/pub-cache/. /seed/'
fi
```

`docker-compose.yml`, servicio `sandbox-runner`: volumen `${HOME}/forjai-deps:${HOME}/forjai-deps` y `SANDBOX_DEPS_ROOT: ${HOME}/forjai-deps`.

- [ ] **Step 4: Correr** — `cd sandbox-runner && mvn -q test` → PASS.

- [ ] **Step 5: Verificación de imágenes** — `bash sandbox/build-images.sh`; confirmar con el proyecto de humo de `.NET` y `Flutter` (crear uno mínimo como en la parte 2) que `VERIFY` sigue en PASS con los NuGet.Config y montajes nuevos.

- [ ] **Step 6: Commit** — `git commit -m "sandbox-runner: PROMOTE_DEPENDENCIES, jobs de dependencias en la API y caché aprobada en VERIFY"`

---

### Task 3: company-core — pedido de paquetes, versiones exactas y baseline

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/DependencyRef.java`
- Create: `app/src/main/java/com/aicompany/core/model/BaselineDependencies.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/DependencyManifest.java`
- Modify: `agent/model/DevelopmentResult.java` (+ `packages`), `agent/model/DevelopmentResultSchema.java`
- Modify: `agent/DevelopmentRuntime.java` (valida `packages` y `pubspec.yaml`)
- Modify: `model/StackProfile.java` (`executionContract()` explica cómo pedir paquetes)
- Test: `DependencyManifestTest.java`, `DevelopmentRuntimeTest.java`, `StackProfileTest.java`

**Interfaces:**
- Produces: `record DependencyRef(String ecosystem, String name, String version)` con `id()` = `ecosystem:name@version`; `BaselineDependencies.contains(DependencyRef)`; `DevelopmentResult(summary, files, packages)` + constructor compatible `(summary, files)`, `record PackageRequest(String name, String version)`; `DependencyManifest.pubspec(String yaml) → Parsed(List<DependencyRef> deps, List<String> errors)`, `DependencyManifest.validateRequests(List<PackageRequest>) → List<String> errors`.

- [ ] **Step 1: Tests (fallan)**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.PackageRequest;
import com.aicompany.core.model.DependencyRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyManifestTest {

    @Test
    void readsExactPubDependenciesAndIgnoresSdkEntries() {
        var parsed = DependencyManifest.pubspec("""
                name: app
                environment:
                  sdk: ^3.5.0
                dependencies:
                  flutter:
                    sdk: flutter
                  equatable: 2.0.5
                dev_dependencies:
                  flutter_test:
                    sdk: flutter
                  flutter_lints: 4.0.0
                flutter:
                  uses-material-design: true
                """);
        assertEquals(List.of(), parsed.errors());
        assertEquals(List.of(new DependencyRef("PUB", "equatable", "2.0.5"), new DependencyRef("PUB", "flutter_lints", "4.0.0")),
                parsed.deps());
    }

    // Review Focus: rangos y path/git son errores corregibles.
    @Test
    void rangesPathAndGitAreRejected() {
        var parsed = DependencyManifest.pubspec("""
                dependencies:
                  a: ^1.0.0
                  b: any
                  c:
                    path: ../c
                  d:
                    git: https://x
                """);
        assertEquals(4, parsed.errors().size(), parsed.errors().toString());
        assertTrue(parsed.errors().get(0).contains("a"));
    }

    @Test
    void nugetRequestsMustBeExact() {
        assertEquals(List.of(), DependencyManifest.validateRequests(List.of(new PackageRequest("Newtonsoft.Json", "13.0.3"))));
        assertEquals(2, DependencyManifest.validateRequests(List.of(new PackageRequest("A", "[1.0,2.0)"),
                new PackageRequest("B; rm", "1.0.0"))).size());
    }
}
```

En `DevelopmentRuntimeTest`:
```java
    @Test
    void aNonExactPackageVersionIsRetried() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DevelopmentResult("r", List.of(new GeneratedFile("web/game/a.cs", "x")),
                        List.of(new DevelopmentResult.PackageRequest("Newtonsoft.Json", "13.*"))))
                .thenReturn(new DevelopmentResult("r", List.of(new GeneratedFile("web/game/a.cs", "x")),
                        List.of(new DevelopmentResult.PackageRequest("Newtonsoft.Json", "13.0.3"))));

        var result = runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        assertEquals("13.0.3", result.packages().get(0).version());
        verify(ceoService).generateDevelopmentArtifact(anyString(),
                argThat(p -> p.contains("CORRECCIÓN") && p.contains("versión exacta")), anyString(), anyString());
    }
```
En `StackProfileTest`:
```java
    @Test
    void theDotnetContractExplainsHowToRequestAPackage() {
        assertTrue(StackProfile.DOTNET_APP.executionContract().contains("\"packages\""));
        assertTrue(StackProfile.FLUTTER_WEB_APP.executionContract().contains("versión exacta"));
    }
```

- [ ] **Step 2: Correr** — `cd app && mvn -q test -Dtest='DependencyManifestTest,DevelopmentRuntimeTest,StackProfileTest'` → FAIL.

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.model;

/** Un paquete de un ecosistema (NUGET | PUB) en una versión exacta. */
public record DependencyRef(String ecosystem, String name, String version) {
    public String id() {
        return ecosystem + ":" + name.toLowerCase(java.util.Locale.ROOT) + "@" + version;
    }
}
```

```java
package com.aicompany.core.model;

import java.util.Set;

/** Paquetes que ya trae cada imagen del sandbox (sandbox/images/*); cuentan como aprobados. */
public final class BaselineDependencies {

    private BaselineDependencies() {
    }

    private static final Set<String> IDS = Set.of(
            "NUGET:xunit@2.5.3", "NUGET:microsoft.net.test.sdk@17.8.0", "NUGET:xunit.runner.visualstudio@2.5.3",
            "NUGET:coverlet.collector@6.0.0", "NUGET:swashbuckle.aspnetcore@6.6.2",
            "NUGET:microsoft.aspnetcore.openapi@8.0.31", "NUGET:godot.net.sdk@4.3.0",
            "PUB:cupertino_icons@1.0.8", "PUB:flutter_lints@4.0.0");

    public static boolean contains(DependencyRef ref) {
        return IDS.contains(ref.id());
    }
}
```

`DevelopmentResult`:
```java
public record DevelopmentResult(String summary, List<GeneratedFile> files, List<PackageRequest> packages) {

    public DevelopmentResult(String summary, List<GeneratedFile> files) {
        this(summary, files, List.of());
    }

    public List<PackageRequest> packagesOrEmpty() {
        return packages == null ? List.of() : packages;
    }

    public record GeneratedFile(String path, String content) {
    }

    /** Paquete NuGet que el agente necesita (parte 3): solo versión exacta; Forjai lo agrega al .csproj de su capa. */
    public record PackageRequest(String name, String version) {
    }
}
```
`DevelopmentResultSchema`: agregar la propiedad opcional `"packages"` (array de `{name, version}` requeridos, `additionalProperties: false`).

`DependencyManifest`:
```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.PackageRequest;
import com.aicompany.core.model.DependencyRef;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Lectura determinista de lo que pide un agente (spec §3): solo versiones exactas. */
public final class DependencyManifest {

    public record Parsed(List<DependencyRef> deps, List<String> errors) {
    }

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    static final Pattern VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$");
    private static final Pattern SECTION = Pattern.compile("^(dependencies|dev_dependencies):\\s*$");
    private static final Pattern ENTRY = Pattern.compile("^  ([A-Za-z0-9_]+):\\s*(.*?)\\s*$");
    private static final Pattern NESTED = Pattern.compile("^    (sdk|path|git|hosted|version):\\s*(.*?)\\s*$");

    private DependencyManifest() {
    }

    public static List<String> validateRequests(List<PackageRequest> requests) {
        var errors = new ArrayList<String>();
        for (var r : requests == null ? List.<PackageRequest>of() : requests) {
            if (r == null || r.name() == null || !NAME.matcher(r.name()).matches()) {
                errors.add("packages: nombre de paquete inválido \"" + (r == null ? null : r.name()) + "\".");
            } else if (r.version() == null || !VERSION.matcher(r.version()).matches()) {
                errors.add("packages: " + r.name() + " necesita una versión exacta (p. ej. 13.0.3), no \"" + r.version() + "\".");
            }
        }
        return errors;
    }

    public static Parsed pubspec(String yaml) {
        var deps = new ArrayList<DependencyRef>();
        var errors = new ArrayList<String>();
        var inSection = false;
        String pending = null;
        for (var line : (yaml == null ? "" : yaml).split("\\R")) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            if (!line.startsWith(" ")) {
                inSection = SECTION.matcher(line).matches();
                pending = null;
                continue;
            }
            if (!inSection) {
                continue;
            }
            var nested = NESTED.matcher(line);
            if (nested.matches() && pending != null) {
                if (!"sdk".equals(nested.group(1))) {
                    errors.add("pubspec.yaml: " + pending + " usa " + nested.group(1) + ": solo se permiten paquetes de pub.dev con versión exacta.");
                }
                pending = null;
                continue;
            }
            var entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            var name = entry.group(1);
            var version = entry.group(2).replace("\"", "").replace("'", "");
            if (version.isEmpty()) {
                pending = name;
            } else if (VERSION.matcher(version).matches()) {
                deps.add(new DependencyRef("PUB", name, version));
            } else {
                errors.add("pubspec.yaml: " + name + " necesita una versión exacta (p. ej. 2.0.5), no \"" + version + "\".");
            }
        }
        return new Parsed(deps, errors);
    }
}
```

`DevelopmentRuntime.verifyGenerated`: agregar `retryable.addAll(DependencyManifest.validateRequests(result.packagesOrEmpty()))` y, si entre los archivos hay `pubspec.yaml`, `retryable.addAll(DependencyManifest.pubspec(content).errors())`.

`StackProfile.executionContract()`: en `DOTNET_APP` y `GODOT_DOTNET_GAME` agregar: *"Si necesitas un paquete NuGet que no está en la lista, decláralo en \"packages\": [{\"name\": \"...\", \"version\": \"x.y.z\"}] con versión exacta; Forjai lo agrega a tu .csproj si pasa los chequeos."*; en `FLUTTER_WEB_APP`: *"Otras dependencias de pub.dev: en pubspec.yaml con versión exacta (p. ej. equatable: 2.0.5, sin ^); se aprueban si pasan los chequeos."* y cambiar `cupertino_icons ^1.0.8` / `flutter_lints ^4.0.0` por `1.0.8` / `4.0.0`.

- [ ] **Step 4: Correr** — suite completa `bash /tmp/suite.sh` → verde.

- [ ] **Step 5: Commit** — `git commit -m "Pedido de paquetes con versión exacta: packages en DevelopmentResult, pubspec.yaml y baseline de las imágenes"`

---

### Task 4: company-core — `ProjectScaffold` con los paquetes pedidos

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/ProjectScaffold.java`
- Test: `app/src/test/java/com/aicompany/core/model/ProjectScaffoldTest.java`

**Interfaces:**
- Consumes: `DependencyRef` (Task 3).
- Produces: `ProjectScaffold.generate(StackProfile, List<String> contexts, Map<String, List<DependencyRef>> packagesByProject)` (clave = ruta del `.csproj`); la sobrecarga de 2 argumentos delega con `Map.of()`.

- [ ] **Step 1: Test (falla)**

```java
    @Test
    void requestedPackagesAreAddedToTheirLayersProject() {
        var files = ProjectScaffold.generate(StackProfile.DOTNET_APP, List.of("Tareas"),
                Map.of("src/Tareas.Infrastructure/Tareas.Infrastructure.csproj",
                        List.of(new DependencyRef("NUGET", "Newtonsoft.Json", "13.0.3")))).stream()
                .collect(Collectors.toMap(GeneratedFile::path, GeneratedFile::content));
        assertTrue(files.get("src/Tareas.Infrastructure/Tareas.Infrastructure.csproj")
                .contains("<PackageReference Include=\"Newtonsoft.Json\" Version=\"13.0.3\" />"));
        assertFalse(files.get("src/Tareas.Domain/Tareas.Domain.csproj").contains("Newtonsoft"));
    }
```

- [ ] **Step 2: Correr** → FAIL. **Step 3: Implementar**: en `generate`, para cada proyecto, si `packagesByProject` tiene entradas, agregar un `<ItemGroup>` con un `PackageReference` por paquete (misma forma que `TEST_PACKAGES`), después de los paquetes existentes. **Step 4: Correr** → PASS. **Step 5: Commit** — `git commit -m "ProjectScaffold agrega los paquetes pedidos al .csproj de cada capa"`

---

### Task 5: company-core — OSV, licencias y política

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/OsvClient.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/LicenseClassifier.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/DependencyPolicy.java`
- Modify: `app/src/main/java/com/aicompany/core/config/CoreConfig.java` (bean `OsvClient`)
- Test: `OsvClientTest.java`, `LicenseClassifierTest.java`, `DependencyPolicyTest.java`

**Interfaces:**
- Produces: `OsvClient.blockingVulnerabilities(DependencyRef) → Optional<List<String>>` (vacío = OSV no respondió; lista = ids HIGH/CRITICAL o de severidad desconocida); `LicenseClassifier.nuget(String expression) → Optional<String>` y `.text(String licenseText) → Optional<String>` (licencia permitida reconocida); `record PackageFacts(DependencyRef ref, String license, boolean buildCode, List<String> buildCodeFiles, Optional<List<String>> vulnerabilities)`; `DependencyPolicy.decide(PackageFacts) → Decision(String status, List<String> reasons)` con `status` ∈ `APPROVED|PENDING_APPROVAL`.

- [ ] **Step 1: Tests (fallan)**

```java
class LicenseClassifierTest {
    @Test
    void nugetExpressions() {
        assertEquals(Optional.of("MIT"), LicenseClassifier.nuget("MIT"));
        assertEquals(Optional.of("MIT OR GPL-3.0"), LicenseClassifier.nuget("MIT OR GPL-3.0"));
        assertEquals(Optional.empty(), LicenseClassifier.nuget("MIT AND GPL-3.0"));
        assertEquals(Optional.empty(), LicenseClassifier.nuget(null));
    }

    @Test
    void licenseTexts() {
        assertEquals(Optional.of("MIT"), LicenseClassifier.text("MIT License\n\nPermission is hereby granted, free of charge, to any person"));
        assertEquals(Optional.of("Apache-2.0"), LicenseClassifier.text("Apache License\nVersion 2.0, January 2004"));
        assertEquals(Optional.of("BSD-3-Clause"), LicenseClassifier.text("Redistribution and use in source and binary forms ... Neither the name of"));
        assertEquals(Optional.of("BSD-2-Clause"), LicenseClassifier.text("Redistribution and use in source and binary forms, with or without modification"));
        assertEquals(Optional.empty(), LicenseClassifier.text("GNU GENERAL PUBLIC LICENSE Version 3"));
    }
}
```

```java
class DependencyPolicyTest {
    private static final DependencyRef REF = new DependencyRef("NUGET", "A", "1.0.0");

    @Test
    void approvesWhenEverythingPasses() {
        assertEquals("APPROVED", DependencyPolicy.decide(new PackageFacts(REF, "MIT", false, List.of(), Optional.of(List.of()))).status());
    }

    @Test
    void eachFailingCheckLeavesItPendingWithTheExactReason() {
        var d = DependencyPolicy.decide(new PackageFacts(REF, null, true, List.of("build/a.targets"), Optional.of(List.of("GHSA-1"))));
        assertEquals("PENDING_APPROVAL", d.status());
        assertEquals(3, d.reasons().size(), d.reasons().toString());
        assertTrue(d.reasons().stream().anyMatch(r -> r.contains("GHSA-1")));
        assertTrue(d.reasons().stream().anyMatch(r -> r.contains("build/a.targets")));
    }

    // Review Focus: OSV caído → pendiente, nunca aprobado a ciegas.
    @Test
    void anUnreachableOsvIsNotAnApproval() {
        var d = DependencyPolicy.decide(new PackageFacts(REF, "MIT", false, List.of(), Optional.empty()));
        assertEquals("PENDING_APPROVAL", d.status());
        assertTrue(d.reasons().get(0).contains("OSV"));
    }
}
```

`OsvClientTest` (con `MockRestServiceServer`): `POST /v1/query` con `{"package":{"name":"A","ecosystem":"NuGet"},"version":"1.0.0"}` → `{"vulns":[{"id":"GHSA-1","database_specific":{"severity":"HIGH"}},{"id":"GHSA-2","database_specific":{"severity":"LOW"}}]}` → `Optional.of(List.of("GHSA-1"))`; `{}` → `Optional.of(List.of())`; un vuln sin `database_specific.severity` → cuenta como bloqueante; HTTP 503 → `Optional.empty()`; `PUB` usa `"ecosystem":"Pub"`.

- [ ] **Step 2: Correr** → FAIL.

- [ ] **Step 3: Implementar**

```java
public final class LicenseClassifier {
    static final Set<String> ALLOWED = Set.of("MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Zlib");

    public static Optional<String> nuget(String expression) {
        if (expression == null || expression.isBlank()) return Optional.empty();
        var e = expression.replace("(", "").replace(")", "").strip();
        if (e.contains(" AND ")) {
            return Arrays.stream(e.split(" AND ")).map(String::strip).allMatch(ALLOWED::contains) ? Optional.of(expression) : Optional.empty();
        }
        return Arrays.stream(e.split(" OR ")).map(String::strip).anyMatch(ALLOWED::contains) ? Optional.of(expression) : Optional.empty();
    }

    public static Optional<String> text(String license) {
        if (license == null) return Optional.empty();
        var t = license.replaceAll("\\s+", " ");
        if (t.contains("Permission is hereby granted, free of charge")) return Optional.of("MIT");
        if (t.contains("Apache License") && t.contains("Version 2.0")) return Optional.of("Apache-2.0");
        if (t.contains("Redistribution and use in source and binary forms"))
            return Optional.of(t.contains("Neither the name") ? "BSD-3-Clause" : "BSD-2-Clause");
        if (t.contains("Permission to use, copy, modify, and/or distribute")) return Optional.of("ISC");
        if (t.contains("This software is provided 'as-is'") && t.contains("Altered source versions")) return Optional.of("Zlib");
        return Optional.empty();
    }
}
```

```java
public final class DependencyPolicy {
    public record PackageFacts(DependencyRef ref, String license, boolean buildCode, List<String> buildCodeFiles,
                               Optional<List<String>> vulnerabilities) { }
    public record Decision(String status, List<String> reasons) { }

    public static Decision decide(PackageFacts f) {
        var reasons = new ArrayList<String>();
        if (f.vulnerabilities().isEmpty()) {
            reasons.add("No se pudo consultar OSV: no se aprueba sin revisar vulnerabilidades.");
        } else if (!f.vulnerabilities().get().isEmpty()) {
            reasons.add("Vulnerabilidades HIGH/CRITICAL o sin severidad: " + f.vulnerabilities().get());
        }
        if (f.license() == null) {
            reasons.add("Licencia no reconocida o fuera de la lista permitida (MIT, Apache-2.0, BSD-2/3-Clause, ISC, Zlib).");
        }
        if (f.buildCode()) {
            reasons.add("Trae código que se ejecuta al compilar: " + f.buildCodeFiles());
        }
        return new Decision(reasons.isEmpty() ? "APPROVED" : "PENDING_APPROVAL", reasons);
    }
}
```

`OsvClient` (bean en `CoreConfig` con `RestClient` a `https://api.osv.dev`, timeout 15 s):
```java
public class OsvClient {
    private final RestClient client;
    public OsvClient(RestClient client) { this.client = client; }

    @SuppressWarnings("unchecked")
    public Optional<List<String>> blockingVulnerabilities(DependencyRef ref) {
        try {
            var body = Map.of("package", Map.of("name", ref.name(), "ecosystem", "NUGET".equals(ref.ecosystem()) ? "NuGet" : "Pub"),
                    "version", ref.version());
            var response = client.post().uri("/v1/query").body(body).retrieve().body(Map.class);
            var vulns = response == null ? List.<Map<String, Object>>of()
                    : (List<Map<String, Object>>) response.getOrDefault("vulns", List.of());
            var blocking = new ArrayList<String>();
            for (var v : vulns) {
                var db = (Map<String, Object>) v.getOrDefault("database_specific", Map.of());
                var severity = String.valueOf(db.getOrDefault("severity", "UNKNOWN")).toUpperCase(Locale.ROOT);
                if (!severity.equals("LOW") && !severity.equals("MODERATE") && !severity.equals("MEDIUM")) {
                    blocking.add(String.valueOf(v.get("id")));
                }
            }
            return Optional.of(blocking);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Correr** → PASS. **Step 5: Commit** — `git commit -m "Chequeos de dependencias: OSV, licencias permitidas y política de aprobación"`

---

### Task 6: company-core — memoria de dependencias y orquestación

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/DependencyMemoryService.java`
- Create: `app/src/main/java/com/aicompany/core/service/DependencyService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/SandboxRunnerClient.java` (+ `fetchDependencies`, `promoteDependencies`)
- Modify: `app/src/main/java/com/aicompany/core/model/SandboxResult.java` (records de fetch)
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java` (constraint `Dependency.id`)
- Test: `DependencyServiceTest.java`, `SandboxRunnerClientTest.java`

**Interfaces:**
- Consumes: `DependencyRef`, `BaselineDependencies` (Task 3); `OsvClient`, `LicenseClassifier`, `DependencyPolicy` (Task 5).
- Produces: `DependencyMemoryService.status(DependencyRef) → Optional<String>`, `.record(DependencyRef, status, approvedBy, reasons, license, requestedByAgent, missionId, jobId)`, `.decide(String id, String status, String approvedBy)`, `.find(String id) → Optional<Map<String,Object>>`, `.list() → List<Map<String,Object>>`; `DependencyService.resolve(String missionId, String agentId, List<DependencyRef>) → Outcome(List<DependencyRef> pending, List<DependencyRef> approvedNow, String error)`; `SandboxRunnerClient.fetchDependencies(String ecosystem, List<DependencyRef>) → Optional<FetchResult>` y `.promoteDependencies(String ecosystem, String jobId, List<DependencyRef>) → boolean`.

- [ ] **Step 1: Tests (fallan)** — `DependencyServiceTest` con mocks de `DependencyMemoryService`, `SandboxRunnerClient`, `OsvClient`, `CompanyEventPublisher`:
  1. `alreadyApprovedOrBaselinePackagesAreNotFetchedAgain` (Review Focus): `status(ref)` = `APPROVED` y un baseline → `verifyNoInteractions(runner)`, `Outcome.pending` vacío.
  2. `aCleanPackageIsApprovedByPolicyAndPromoted`: runner devuelve `PASS` con `Newtonsoft.Json 13.0.3` licencia `MIT`, OSV `Optional.of(List.of())` → `record(..., "APPROVED", "policy", ...)`, `promoteDependencies("NUGET", jobId, [ref])`, evento `EMPRESA_DEPENDENCY_APPROVED`.
  3. `aVulnerableTransitivePackageLeavesItPending`: el fetch trae también un transitivo con OSV `["GHSA-9"]` → ese queda `PENDING_APPROVAL`, `EMPRESA_DEPENDENCY_REQUESTED`, y `Outcome.pending` lo contiene.
  4. `aFailedFetchIsAPendingOutcomeWithTheError`: runner `FAIL` → `Outcome.error` con la salida, `pending` = los pedidos.

- [ ] **Step 2: Correr** → FAIL.

- [ ] **Step 3: Implementar**
  - `DependencyMemoryService`: nodo `(:Dependency {id, ecosystem, name, version, status, approvedBy, reasons, license, requestedByAgent, missionId, jobId, createdAt, decidedAt})` con `MERGE` por `id`; constraint de unicidad en `CompanyMemoryService.initializeSchema()`.
  - `DependencyService.resolve`: filtra baseline y `APPROVED`; agrupa por ecosistema; `fetchDependencies`; por cada `FetchedPackage` no aprobado: licencia (`LicenseClassifier.nuget` o `.text`), OSV, `DependencyPolicy.decide`, `record`; promueve los `APPROVED` en un solo `promoteDependencies`; si la promoción falla, esos quedan `PENDING_APPROVAL` con el motivo. Publica `EMPRESA_DEPENDENCY_REQUESTED` por pendiente y `EMPRESA_DEPENDENCY_APPROVED` por aprobado.
  - `SandboxRunnerClient`: `POST /jobs` con `jobType` `FETCH_DEPENDENCIES`/`PROMOTE_DEPENDENCIES` y `dependencies`; nunca lanza (vacío/false + `lastError`).

- [ ] **Step 4: Correr** → PASS. **Step 5: Commit** — `git commit -m "Dependencias: memoria en Neo4j, orquestación de fetch, política y promoción"`

---

### Task 7: company-core — API `/api/company/dependencies`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/controller/DependencyController.java`
- Test: `app/src/test/java/com/aicompany/core/controller/DependencyControllerTest.java`

**Interfaces:**
- Consumes: `DependencyMemoryService`, `SandboxRunnerClient.promoteDependencies` (Task 6).
- Produces: `GET /api/company/dependencies`, `PUT /api/company/dependencies/{id}/approve` (promueve y deja `APPROVED`, `approvedBy='founder'`, evento `EMPRESA_DEPENDENCY_APPROVED`), `PUT /api/company/dependencies/{id}/reject` (`REJECTED`, evento `EMPRESA_DEPENDENCY_REJECTED`). 404 si no existe.

- [ ] **Step 1: Tests (fallan)**: aprobar llama a `promoteDependencies` con el `jobId` guardado y luego `decide(id, "APPROVED", "founder")`; **Review Focus**: si la promoción falla → `IllegalStateException` (500, convención del proyecto) y `decide` nunca se llama; rechazar no llama al runner; id inexistente → 404.
- [ ] **Step 2–4**: implementar con la convención de controllers del proyecto (sin manejo fino salvo 404). **Step 5: Commit** — `git commit -m "API de dependencias: listar, aprobar (promueve) y rechazar"`

---

### Task 8: company-core — integración en `DevelopmentTeamStrategy`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: `DevelopmentResult.packagesOrEmpty()` (Task 3), `ProjectScaffold.generate(..., packagesByProject)` (Task 4), `DependencyManifest.pubspec` (Task 3), `DependencyService.resolve` (Task 6).

- [ ] **Step 1: Tests (fallan)**
  1. `requestedNugetPackagesAreAddedToTheScaffoldBeforeVerifying`: Mila (owner `src/Combate.Application`) devuelve `packages=[Newtonsoft.Json 13.0.3]`, `resolve` → sin pendientes → commit `M-1-DEPENDENCIES` de Forjai con `src/Combate.Application/Combate.Application.csproj` conteniendo el `PackageReference`, y `sandbox.verify` sobre ese sha.
  2. `pendingDependenciesSkipTheSandboxAndLeaveItUnvalidated`: `resolve` → `pending=[A@1.0.0]` → `verifyNoInteractions(sandbox)`, `recordStaticValidation(..., "UNVALIDATED", ...)` y el estado verificable dice "Dependencias pendientes de aprobación (🔴): NUGET:a@1.0.0".
  3. `flutterDependenciesComeFromThePubspecAtHead`: `readFileAtCommit(..., "pubspec.yaml")` con `equatable: 2.0.5` → `resolve("M-1", ..., [PUB:equatable@2.0.5])`.

- [ ] **Step 2: Correr** → FAIL.

- [ ] **Step 3: Implementar**: después del bucle de generación y antes de los chequeos:
  - .NET: juntar `packagesOrEmpty()` de cada resultado; asignar cada paquete al `.csproj` de la primera capa del `ownedPaths` del agente (`StackProfile.projectFiles` ∩ rutas del agente); si hay alguno, regenerar solo esos `.csproj` con `ProjectScaffold.generate(profile, contexts, packagesByProject)` y commitearlos como `Forjai` (`<missionId>-DEPENDENCIES`), sumando el commit a `committed`.
  - Flutter: `DependencyManifest.pubspec(readFileAtCommit(HEAD, "pubspec.yaml")).deps()`.
  - `dependencyService.resolve(missionId, "engineering", refs)`; si `pending` no vacío o `error` → no se llama a `sandbox.verify`; `sandboxError = "Dependencias pendientes de aprobación (🔴): " + ids`.
  - `verifiableState`: línea "Dependencias: N aprobadas por política, M pendientes (🔴): ...".

- [ ] **Step 4: Correr** → suite completa en verde. **Step 5: Commit** — `git commit -m "Dependencias integradas en la misión: scaffold con paquetes pedidos, fetch/política y VERIFY solo sin pendientes"`

---

### Task 9: Documentación y verificación en vivo

- [ ] **Step 1: Despliegue** — `bash sandbox/build-images.sh` (egress-proxy + siembra de `~/forjai-deps/pub`), `docker compose build && up -d` con el script de redeploy seguro.
- [ ] **Step 2: Humo del runner** — `FETCH_DEPENDENCIES` de `Newtonsoft.Json 13.0.3` y de `equatable 2.0.5` por la API; confirmar licencia y metadatos; `PROMOTE`; y un `VERIFY` de un proyecto mínimo que los usa, en PASS sin red.
- [ ] **Step 3: Misión real .NET** que pide un paquete (instrucción que lo haga natural, p. ej. serializar las tareas a JSON con `Newtonsoft.Json`) → `VERIFIED`, con el nodo `Dependency` `APPROVED` por `policy`.
- [ ] **Step 4: Control** — pedir por la API de runner + `DependencyService` un paquete con vulnerabilidad conocida (buscar uno real en OSV, p. ej. una versión vieja de `System.Text.Encodings.Web` o `Newtonsoft.Json 12.0.1`) y confirmar `PENDING_APPROVAL` con el motivo; aprobarlo a mano con `PUT /approve` y verificar la promoción.
- [ ] **Step 5: Docs** — `CLAUDE.md` (sección Sandbox: dependencias gobernadas, `~/forjai-deps`, API), `docs/EVENTS.md` (3 eventos), `docs/HISTORY.md` (verificación en vivo). **Step 6: Commit** — `git commit -m "Documentar las dependencias gobernadas y su verificación en vivo"`

---

## Self-review

- **Cobertura del spec §3 y su revisión**: pedido y versiones exactas (T3), scaffold con paquetes (T4), proxy/fetch aislado y metadatos (T1), OSV/licencia/código en el build y política (T5), persistencia/API/eventos (T6, T7), caché en `VERIFY` (T2), pendientes → `UNVALIDATED` (T8), verificación en vivo con aprobado y control (T9).
- **Verificado antes de escribir** (prueba del 2026-09-27): squid con lista blanca en red interna (GitHub bloqueado), `dotnet restore` y `flutter pub get` por el proxy, `.nuspec` con `<license type="expression">`, `fallbackPackageFolders` de solo lectura con `--read-only`, pub offline con `:O` (con `:ro` falla).
- **Tipos**: `DependencyRef` (T3) en T4–T8; `FetchedPackage`/`FetchResult` (T1) espejados en `SandboxResult` de company-core (T6); `DependencyRequest.Package` en el runner.

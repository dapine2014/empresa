# Sandbox de verificación — Parte 2: `sandbox-runner`, imágenes y estado `VERIFIED` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cada misión de Engineering compila, corre sus tests y arranca el código generado dentro de contenedores aislados (Podman sin root, sin red), y el resultado real define un nuevo estado `VERIFIED`.

**Architecture:** Un servicio nuevo `sandbox-runner` (módulo Maven aparte, Spring Boot) es el único que habla con Podman (socket del usuario, sin root). Recibe `POST /jobs {jobType: VERIFY, missionId, commitSha, stackProfile}`, extrae el commit a un directorio de trabajo y corre, en contenedores descartables, los pasos fijos del perfil (`restore`, `build`, `test`, `smoke`) definidos por scripts dentro de cada imagen. `company-core` lo llama con `SandboxRunnerClient` desde `DevelopmentTeamStrategy`, después de los chequeos deterministas y antes de la revisión de Vera; `StaticValidationStatus` gana `VERIFIED`. Las dependencias iniciales vienen precargadas en cada imagen (la parte 3 agrega el catálogo que crece).

**Tech Stack:** Java 21, Spring Boot 4.1.1 (ambos módulos), Podman 5 sin root (host) + cliente `podman` remoto en el runner, .NET SDK 8, Godot 4.3 .NET, Flutter 3.24, Chrome headless, React/TS (frontend).

**Spec:** `docs/superpowers/specs/2026-09-26-sandbox-verification-design.md` (secciones 2 y 4)

## Global Constraints

- Rama `sandbox-runner`. Commits con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- `company-core` **nunca** accede a un motor de contenedores; solo llama a la API del runner.
- El runner **no acepta comandos**: imagen y pasos salen de su catálogo `ExecutionProfile` según el id; id desconocido → HTTP 400 y nada se ejecuta.
- Cada contenedor de `VERIFY`: `--network=none`, `--read-only` con `/tmp` en memoria, `--cap-drop=ALL`, `--security-opt=no-new-privileges`, `--userns=keep-id`, `--memory=4g`, `--cpus=4`, `--pids-limit=512`, `--rm`. Timeouts: build 600 s, test 600 s, smoke 180 s, restore 300 s.
- El runner no publica puertos al host; autenticación con header `X-Sandbox-Token` = `SANDBOX_RUNNER_TOKEN`.
- Rutas del host montadas en el runner **en la misma ruta absoluta** (para que Podman del host las resuelva): `${HOME}/forjai-products` (solo lectura) y `${HOME}/forjai-sandbox-work` (lectura/escritura). El runner corre como el usuario del host (`${UID}:${GID}`).
- Estados de paso: `PASS | FAIL | TIMEOUT | SKIPPED`; un paso que falla detiene el resto (`SKIPPED`).
- Estado final: `FAILED` si falla un chequeo determinista, un paso del sandbox, hay `BLOCKER`/`MAJOR`, o **0 tests pasados**; `UNVALIDATED` si el sandbox no pudo correr o la revisión no se completó; `VERIFIED` si todo pasa con ≥1 test y sin `BLOCKER`/`MAJOR`.
- Frase fija con `VERIFIED`: `Compiló, pasaron N tests y arrancó en el sandbox. No garantiza que el producto esté completo ni que no tenga errores fuera de lo probado.`
- Suite de `company-core` en verde en cada tarea (baseline 375). El runner tiene su propia suite (`cd sandbox-runner && mvn test`).

## Review Focus

- El commit a verificar no existe en el workspace (misión borrada o sha incorrecto): el runner debe responder con un resultado `FAIL` en un paso `checkout`, no con un 500 — test en Task 3.
- La salida de un paso es gigante (build con miles de líneas): `outputTail` debe quedar acotado a 20 KB y conservar el final (donde está el error) — test en Task 3.
- El reporte de tests no existe (el paso `test` falló antes de escribirlo): el conteo debe ser 0/0, no una excepción — test en Task 2.
- El runner no responde o tarda más que el timeout del cliente: la misión debe quedar `UNVALIDATED` con el motivo, nunca `VERIFIED` ni colgada — test en Task 6.
- Los chequeos deterministas ya fallaron: no tiene sentido gastar minutos compilando; los pasos deben quedar `SKIPPED` con el motivo — test en Task 7.

---

## File Structure

**Nuevo módulo `sandbox-runner/`:**
- `sandbox-runner/pom.xml`, `Dockerfile`
- `src/main/java/com/forjai/sandbox/SandboxRunnerApplication.java`
- `.../ExecutionProfile.java` — catálogo de ejecución (imagen, pasos, timeouts).
- `.../SandboxResult.java` — contrato de respuesta.
- `.../PodmanCommandBuilder.java` — argumentos con el aislamiento obligatorio.
- `.../ProcessExecutor.java` — interfaz (+ `SystemProcessExecutor`) para ejecutar procesos con timeout.
- `.../TestReportParser.java` — TRX y JSON de `flutter test`.
- `.../VerifyJobRunner.java` — checkout + pasos + resultado.
- `.../JobController.java` — `POST /jobs` con token.

**Imágenes:** `sandbox/images/{dotnet-app,godot-dotnet-game,flutter-web-app}/{Dockerfile,run.sh}`, `sandbox/build-images.sh`.

**`company-core`:** `model/SandboxResult.java`, `service/SandboxRunnerClient.java`, cambios en `StaticValidationStatus`, `AgentTask`, `MissionMemoryService`, `DevelopmentTeamStrategy`, `ProductStatusService`, `application.yml`, `docker-compose.yml`; frontend `types.ts`, `MissionDetailPage.tsx`.

---

### Task 1: Módulo `sandbox-runner`: catálogo de ejecución y constructor de comandos Podman

**Files:**
- Create: `sandbox-runner/pom.xml`
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/SandboxRunnerApplication.java`
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/ExecutionProfile.java`
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/PodmanCommandBuilder.java`
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/ExecutionProfileTest.java`
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/PodmanCommandBuilderTest.java`

**Interfaces:**
- Produces: `enum ExecutionProfile {DOTNET_APP, GODOT_DOTNET_GAME, FLUTTER_WEB_APP}` con `String image()`, `List<Step> steps()`, `static Optional<ExecutionProfile> parse(String)`; `record Step(String name, int timeoutSeconds)`; `PodmanCommandBuilder(String podmanUrl)` con `List<String> run(ExecutionProfile profile, Step step, Path workDir)`.

- [ ] **Step 1: `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>

    <groupId>com.forjai</groupId>
    <artifactId>sandbox-runner</artifactId>
    <version>0.1.0-SNAPSHOT</version>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

(Si `spring-boot-starter-web` no existe en Boot 4 con ese nombre, usar el mismo starter web que declara `app/pom.xml` y anotarlo como ruling.)

- [ ] **Step 2: Tests (fallan: no hay clases)**

```java
package com.forjai.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionProfileTest {

    @Test
    void onlyCatalogIdsAreAccepted() {
        assertEquals(Optional.of(ExecutionProfile.DOTNET_APP), ExecutionProfile.parse("DOTNET_APP"));
        assertEquals(Optional.empty(), ExecutionProfile.parse("rm -rf /"));
        assertEquals(Optional.empty(), ExecutionProfile.parse(null));
    }

    @Test
    void everyProfileRunsTheSameFixedStepsInOrder() {
        for (var profile : ExecutionProfile.values()) {
            assertEquals(List.of("restore", "build", "test", "smoke"),
                    profile.steps().stream().map(ExecutionProfile.Step::name).toList());
            assertTrue(profile.image().startsWith("localhost/forjai-sandbox/"));
        }
    }
}
```

```java
package com.forjai.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PodmanCommandBuilderTest {

    private final PodmanCommandBuilder builder = new PodmanCommandBuilder("unix:///run/podman/podman.sock");

    @Test
    void everyRunCarriesTheMandatoryIsolation() {
        var step = ExecutionProfile.GODOT_DOTNET_GAME.steps().get(1);
        var args = builder.run(ExecutionProfile.GODOT_DOTNET_GAME, step, Path.of("/home/alex/forjai-sandbox-work/job-1"));

        assertEquals("podman", args.get(0));
        assertTrue(args.containsAll(java.util.List.of("--network=none", "--read-only", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--userns=keep-id", "--memory=4g", "--cpus=4",
                "--pids-limit=512", "--rm")), args.toString());
        assertTrue(args.contains("--timeout=" + step.timeoutSeconds()));
        assertTrue(args.contains("/home/alex/forjai-sandbox-work/job-1:/work:Z"));
        assertEquals(java.util.List.of(ExecutionProfile.GODOT_DOTNET_GAME.image(), "/forjai/run.sh", "build"),
                args.subList(args.size() - 3, args.size()));
    }

    @Test
    void theStepNameNeverComesFromOutsideTheCatalog() {
        var args = builder.run(ExecutionProfile.DOTNET_APP, ExecutionProfile.DOTNET_APP.steps().get(0),
                Path.of("/w"));
        assertEquals("restore", args.get(args.size() - 1));
    }
}
```

- [ ] **Step 3: Correr** — `cd sandbox-runner && mvn -q test` → FAIL de compilación.

- [ ] **Step 4: Implementar**

```java
package com.forjai.sandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SandboxRunnerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SandboxRunnerApplication.class, args);
    }
}
```

```java
package com.forjai.sandbox;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Parte de EJECUCIÓN de cada perfil (spec 2026-09-26 §2): imagen y pasos fijos.
 * La parte estructural vive en company-core (StackProfile); lo único compartido es el id.
 * Los comandos de cada paso están dentro de la imagen (/forjai/run.sh <paso>).
 */
public enum ExecutionProfile {

    DOTNET_APP("localhost/forjai-sandbox/dotnet-app:1"),
    GODOT_DOTNET_GAME("localhost/forjai-sandbox/godot-dotnet-game:1"),
    FLUTTER_WEB_APP("localhost/forjai-sandbox/flutter-web-app:1");

    public record Step(String name, int timeoutSeconds) {
    }

    private static final List<Step> STEPS = List.of(
            new Step("restore", 300), new Step("build", 600), new Step("test", 600), new Step("smoke", 180));

    private final String image;

    ExecutionProfile(String image) {
        this.image = image;
    }

    public String image() {
        return image;
    }

    public List<Step> steps() {
        return STEPS;
    }

    public static Optional<ExecutionProfile> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(p -> p.name().equals(id)).findFirst();
    }
}
```

```java
package com.forjai.sandbox;

import java.nio.file.Path;
import java.util.List;

/** Argumentos de `podman run` con el aislamiento obligatorio del spec §2. Nunca recibe comandos externos. */
public class PodmanCommandBuilder {

    private final String podmanUrl;

    public PodmanCommandBuilder(String podmanUrl) {
        this.podmanUrl = podmanUrl;
    }

    public List<String> run(ExecutionProfile profile, ExecutionProfile.Step step, Path workDir) {
        return List.of(
                "podman", "--url", podmanUrl, "run",
                "--rm",
                "--network=none",
                "--read-only",
                "--tmpfs", "/tmp:rw,exec,size=2g",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--userns=keep-id",
                "--memory=4g",
                "--cpus=4",
                "--pids-limit=512",
                "--timeout=" + step.timeoutSeconds(),
                "-e", "HOME=/tmp",
                "-e", "DOTNET_CLI_HOME=/tmp",
                "-e", "DOTNET_CLI_TELEMETRY_OPTOUT=1",
                "-v", workDir + ":/work:Z",
                "-w", "/work",
                profile.image(), "/forjai/run.sh", step.name());
    }
}
```

- [ ] **Step 5: Correr** — `cd sandbox-runner && mvn -q test` → PASS (4 tests).

- [ ] **Step 6: Commit** — `git add sandbox-runner && git commit -m "sandbox-runner: módulo, catálogo de ejecución y comandos Podman aislados" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task 2: `SandboxResult` y `TestReportParser`

**Files:**
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/SandboxResult.java`
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/TestReportParser.java`
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/TestReportParserTest.java`

**Interfaces:**
- Produces: `record SandboxResult(String overall, List<StepResult> steps)` + `record StepResult(String name, String status, int exitCode, long durationMs, String outputTail, int testsPassed, int testsFailed)`, constantes `PASS/FAIL/TIMEOUT/SKIPPED`; `record TestCounts(int passed, int failed)`; `static TestCounts TestReportParser.parse(Path workDir)` (lee `.forjai/results.trx` o `.forjai/results.json`; si no hay, `0/0`).

- [ ] **Step 1: Test (falla)**

```java
package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestReportParserTest {

    @TempDir
    Path work;

    @Test
    void readsDotnetTrxCounters() throws Exception {
        Files.createDirectories(work.resolve(".forjai"));
        Files.writeString(work.resolve(".forjai/results.trx"), """
                <TestRun><ResultSummary outcome="Failed">
                <Counters total="5" executed="5" passed="4" failed="1" error="0" /></ResultSummary></TestRun>
                """);
        assertEquals(new TestReportParser.TestCounts(4, 1), TestReportParser.parse(work));
    }

    @Test
    void readsFlutterMachineJson() throws Exception {
        Files.createDirectories(work.resolve(".forjai"));
        Files.writeString(work.resolve(".forjai/results.json"), String.join("\n",
                "{\"type\":\"start\"}",
                "{\"type\":\"testDone\",\"testID\":1,\"result\":\"success\",\"hidden\":true}",
                "{\"type\":\"testDone\",\"testID\":2,\"result\":\"success\",\"hidden\":false}",
                "{\"type\":\"testDone\",\"testID\":3,\"result\":\"failure\",\"hidden\":false}",
                "{\"type\":\"done\",\"success\":false}"));
        assertEquals(new TestReportParser.TestCounts(1, 1), TestReportParser.parse(work));
    }

    // Review Focus: si el paso falló antes de escribir el reporte, el conteo es 0/0, sin excepción.
    @Test
    void aMissingReportCountsAsZero() {
        assertEquals(new TestReportParser.TestCounts(0, 0), TestReportParser.parse(work));
    }
}
```

- [ ] **Step 2: Correr** — FAIL de compilación.

- [ ] **Step 3: Implementar**

```java
package com.forjai.sandbox;

import java.util.List;

/** Contrato de respuesta del runner (spec 2026-09-26 §2). company-core tiene su copia en model/SandboxResult. */
public record SandboxResult(String overall, List<StepResult> steps) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String SKIPPED = "SKIPPED";

    public record StepResult(String name, String status, int exitCode, long durationMs, String outputTail,
                             int testsPassed, int testsFailed) {
    }
}
```

```java
package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Conteo de tests desde reportes estructurados (TRX de dotnet test, JSON de flutter test), nunca texto libre. */
public final class TestReportParser {

    public record TestCounts(int passed, int failed) {
    }

    private static final Pattern TRX_COUNTERS = Pattern.compile(
            "<Counters[^>]*\\bpassed=\"(\\d+)\"[^>]*\\bfailed=\"(\\d+)\"");

    private TestReportParser() {
    }

    public static TestCounts parse(Path workDir) {
        try {
            var trx = workDir.resolve(".forjai/results.trx");
            if (Files.isRegularFile(trx)) {
                var matcher = TRX_COUNTERS.matcher(Files.readString(trx));
                return matcher.find()
                        ? new TestCounts(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)))
                        : new TestCounts(0, 0);
            }
            var json = workDir.resolve(".forjai/results.json");
            if (Files.isRegularFile(json)) {
                var passed = 0;
                var failed = 0;
                for (var line : Files.readAllLines(json)) {
                    if (!line.contains("\"type\":\"testDone\"") || line.contains("\"hidden\":true")) {
                        continue;
                    }
                    if (line.contains("\"result\":\"success\"")) {
                        passed++;
                    } else {
                        failed++;
                    }
                }
                return new TestCounts(passed, failed);
            }
        } catch (IOException | RuntimeException ex) {
            return new TestCounts(0, 0);
        }
        return new TestCounts(0, 0);
    }
}
```

- [ ] **Step 4: Correr** — PASS (3 tests).

- [ ] **Step 5: Commit** — `git add sandbox-runner && git commit -m "sandbox-runner: contrato SandboxResult y conteo de tests desde reportes estructurados" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task 3: `VerifyJobRunner` — checkout del commit y pasos en orden

**Files:**
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/ProcessExecutor.java`
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/SystemProcessExecutor.java`
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/VerifyJobRunner.java`
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/VerifyJobRunnerTest.java`

**Interfaces:**
- Consumes: `ExecutionProfile`, `PodmanCommandBuilder` (Task 1); `SandboxResult`, `TestReportParser` (Task 2).
- Produces: `interface ProcessExecutor { Execution run(List<String> command, Path directory, long timeoutSeconds); record Execution(int exitCode, String output, boolean timedOut) }`; `VerifyJobRunner(Path productsRoot, Path workRoot, PodmanCommandBuilder podman, ProcessExecutor executor)` con `SandboxResult verify(String missionId, String commitSha, ExecutionProfile profile)`; `static String tail(String output, int maxChars)`.

- [ ] **Step 1: Test (falla)**

```java
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
        return new VerifyJobRunner(products, work, new PodmanCommandBuilder("unix:///s"), executor);
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
```

- [ ] **Step 2: Correr** — FAIL de compilación.

- [ ] **Step 3: Implementar**

```java
package com.forjai.sandbox;

import java.nio.file.Path;
import java.util.List;

public interface ProcessExecutor {

    record Execution(int exitCode, String output, boolean timedOut) {
    }

    Execution run(List<String> command, Path directory, long timeoutSeconds);
}
```

```java
package com.forjai.sandbox;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class SystemProcessExecutor implements ProcessExecutor {

    @Override
    public Execution run(List<String> command, Path directory, long timeoutSeconds) {
        try {
            var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            var output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    return "";
                }
            });
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Execution(-1, output.getNow(""), true);
            }
            return new Execution(process.exitValue(), output.join(), false);
        } catch (IOException ex) {
            return new Execution(-1, "No se pudo ejecutar " + command.get(0) + ": " + ex.getMessage(), false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Execution(-1, "Interrumpido", false);
        }
    }
}
```

```java
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
```

- [ ] **Step 4: Correr** — `cd sandbox-runner && mvn -q test` → PASS.

- [ ] **Step 5: Commit** — `git add sandbox-runner && git commit -m "sandbox-runner: checkout del commit y pasos de VERIFY en orden con timeouts" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task 4: API `POST /jobs` con token + configuración del runner

**Files:**
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/JobController.java`
- Create: `sandbox-runner/src/main/java/com/forjai/sandbox/RunnerConfig.java`
- Create: `sandbox-runner/src/main/resources/application.yml`
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/JobControllerTest.java`

**Interfaces:**
- Consumes: `VerifyJobRunner.verify` (Task 3), `ExecutionProfile.parse` (Task 1).
- Produces: `record JobRequest(String jobType, String missionId, String commitSha, String stackProfile)`; `ResponseEntity<?> JobController.run(String token, JobRequest request)`: 401 sin token válido; 400 para `jobType` distinto de `VERIFY` o perfil desconocido; 200 con `SandboxResult`.

- [ ] **Step 1: Test (falla)**

```java
package com.forjai.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobControllerTest {

    private final VerifyJobRunner runner = mock(VerifyJobRunner.class);
    private final JobController controller = new JobController(runner, "secreto");

    private static JobController.JobRequest request(String type, String profile) {
        return new JobController.JobRequest(type, "M-1", "a".repeat(40), profile);
    }

    @Test
    void rejectsAMissingOrWrongToken() {
        assertEquals(401, controller.run(null, request("VERIFY", "DOTNET_APP")).getStatusCode().value());
        assertEquals(401, controller.run("otro", request("VERIFY", "DOTNET_APP")).getStatusCode().value());
        verifyNoInteractions(runner);
    }

    @Test
    void rejectsUnknownProfilesAndJobTypesWithoutRunningAnything() {
        assertEquals(400, controller.run("secreto", request("VERIFY", "bash -c evil")).getStatusCode().value());
        assertEquals(400, controller.run("secreto", request("EXEC", "DOTNET_APP")).getStatusCode().value());
        verifyNoInteractions(runner);
    }

    @Test
    void runsAValidVerifyJob() {
        var result = new SandboxResult("PASS", java.util.List.of());
        when(runner.verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP)).thenReturn(result);

        var response = controller.run("secreto", request("VERIFY", "DOTNET_APP"));

        assertEquals(200, response.getStatusCode().value());
        assertSame(result, response.getBody());
    }
}
```

- [ ] **Step 2: Correr** — FAIL de compilación.

- [ ] **Step 3: Implementar**

```java
package com.forjai.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Única API del runner (spec §2): nunca acepta comandos; perfil y pasos salen del catálogo. */
@RestController
public class JobController {

    public record JobRequest(String jobType, String missionId, String commitSha, String stackProfile) {
    }

    private final VerifyJobRunner runner;
    private final String token;

    public JobController(VerifyJobRunner runner, @Value("${sandbox.token}") String token) {
        this.runner = runner;
        this.token = token;
    }

    @PostMapping("/jobs")
    public ResponseEntity<?> run(
            @RequestHeader(value = "X-Sandbox-Token", required = false) String requestToken,
            @RequestBody JobRequest request) {

        if (requestToken == null || token == null || token.isBlank()
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        requestToken.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body(Map.of("error", "token inválido"));
        }

        if (!"VERIFY".equals(request.jobType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "jobType no soportado: " + request.jobType()));
        }

        var profile = ExecutionProfile.parse(request.stackProfile());
        if (profile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "stackProfile desconocido: " + request.stackProfile()));
        }

        return ResponseEntity.ok(runner.verify(request.missionId(), request.commitSha(), profile.get()));
    }
}
```

```java
package com.forjai.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class RunnerConfig {

    @Bean
    VerifyJobRunner verifyJobRunner(
            @Value("${sandbox.products-root}") String productsRoot,
            @Value("${sandbox.work-root}") String workRoot,
            @Value("${sandbox.podman-url}") String podmanUrl,
            ProcessExecutor executor) {
        return new VerifyJobRunner(Path.of(productsRoot).toAbsolutePath().normalize(),
                Path.of(workRoot).toAbsolutePath().normalize(), new PodmanCommandBuilder(podmanUrl), executor);
    }
}
```

`application.yml`:

```yaml
server:
  port: ${SANDBOX_PORT:8090}
sandbox:
  token: ${SANDBOX_RUNNER_TOKEN:}
  products-root: ${SANDBOX_PRODUCTS_ROOT:${user.home}/forjai-products}
  work-root: ${SANDBOX_WORK_ROOT:${user.home}/forjai-sandbox-work}
  podman-url: ${SANDBOX_PODMAN_URL:unix:///run/podman/podman.sock}
management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 4: Correr** — `cd sandbox-runner && mvn -q test` → PASS.

- [ ] **Step 5: Commit** — `git add sandbox-runner && git commit -m "sandbox-runner: POST /jobs con token, solo VERIFY y perfiles del catálogo" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task 5: Imágenes de sandbox, `Dockerfile` del runner y `docker-compose`

**Files:**
- Create: `sandbox/images/dotnet-app/Dockerfile`, `sandbox/images/dotnet-app/run.sh`
- Create: `sandbox/images/godot-dotnet-game/Dockerfile`, `sandbox/images/godot-dotnet-game/run.sh`
- Create: `sandbox/images/flutter-web-app/Dockerfile`, `sandbox/images/flutter-web-app/run.sh`
- Create: `sandbox/build-images.sh`
- Create: `sandbox-runner/Dockerfile`
- Modify: `docker-compose.yml`

Sin tests unitarios (infraestructura); la verificación es construir y ejecutar (Step 6 y Task 9). Cada `run.sh` implementa los 4 pasos con comandos fijos; la solución .NET la genera el script (`dotnet new sln` + `dotnet sln add` de todos los `.csproj`) para no depender de un `.sln` escrito por el modelo.

- [ ] **Step 1: `sandbox/images/dotnet-app/Dockerfile` y `run.sh`**

```dockerfile
FROM mcr.microsoft.com/dotnet/sdk:8.0
# Feed offline con el catálogo inicial de paquetes (la parte 3 lo hace crecer con aprobación).
RUN mkdir -p /opt/seed && cd /opt/seed \
    && dotnet new xunit -n Seed -o /opt/seed/Seed \
    && dotnet new webapi -n SeedApi -o /opt/seed/SeedApi \
    && dotnet restore /opt/seed/Seed --packages /opt/nuget-packages \
    && dotnet restore /opt/seed/SeedApi --packages /opt/nuget-packages \
    && rm -rf /opt/seed
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY run.sh /forjai/run.sh
RUN chmod 0755 /forjai/run.sh
```

```bash
#!/bin/bash
# Pasos fijos de DOTNET_APP (spec 2026-09-26 §2). Sin red: solo el feed precargado en /opt/nuget-packages.
set -euo pipefail
cd /work
mkdir -p .forjai
export NUGET_PACKAGES=/opt/nuget-packages
SLN=/tmp/Forjai.sln
make_sln() {
  dotnet new sln -n Forjai -o /tmp --force >/dev/null
  find /work -name '*.csproj' -not -path '*/.forjai/*' -print0 | xargs -0 -r dotnet sln "$SLN" add >/dev/null
}
case "$1" in
  restore) make_sln; dotnet restore "$SLN" --source /opt/nuget-packages ;;
  build)   make_sln; dotnet build "$SLN" --no-restore -c Release ;;
  test)    make_sln; dotnet test "$SLN" --no-build -c Release --logger "trx;LogFileName=results.trx" --results-directory /work/.forjai ;;
  smoke)
    API=$(find /work/src -maxdepth 2 -name '*.Api.csproj' | head -n1)
    [ -n "$API" ] || { echo "No hay proyecto src/*.Api"; exit 1; }
    dotnet run --no-build -c Release --project "$API" --urls http://127.0.0.1:5080 > /tmp/api.log 2>&1 &
    for i in $(seq 1 60); do
      if curl -fsS http://127.0.0.1:5080/health >/dev/null 2>&1; then echo "GET /health 200"; exit 0; fi
      sleep 1
    done
    cat /tmp/api.log; echo "La API no respondió GET /health en 60 s"; exit 1 ;;
  *) echo "paso desconocido: $1"; exit 2 ;;
esac
```

- [ ] **Step 2: `sandbox/images/godot-dotnet-game/Dockerfile` y `run.sh`**

```dockerfile
FROM mcr.microsoft.com/dotnet/sdk:8.0
RUN apt-get update && apt-get install -y --no-install-recommends unzip wget ca-certificates libfontconfig1 \
    && rm -rf /var/lib/apt/lists/*
RUN wget -q https://github.com/godotengine/godot/releases/download/4.3-stable/Godot_v4.3-stable_mono_linux_x86_64.zip \
    && unzip -q Godot_v4.3-stable_mono_linux_x86_64.zip -d /opt \
    && mv /opt/Godot_v4.3-stable_mono_linux_x86_64 /opt/godot \
    && ln -s /opt/godot/Godot_v4.3-stable_mono_linux.x86_64 /usr/local/bin/godot \
    && rm Godot_v4.3-stable_mono_linux_x86_64.zip
RUN mkdir -p /opt/seed && cd /opt/seed \
    && dotnet new xunit -n Seed -o /opt/seed/Seed \
    && dotnet restore /opt/seed/Seed --packages /opt/nuget-packages \
    && printf '<Project Sdk="Godot.NET.Sdk/4.3.0"><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>' > /opt/seed/G.csproj \
    && dotnet restore /opt/seed/G.csproj --packages /opt/nuget-packages \
    && rm -rf /opt/seed
COPY run.sh /forjai/run.sh
RUN chmod 0755 /forjai/run.sh
```

```bash
#!/bin/bash
# Pasos fijos de GODOT_DOTNET_GAME. El dominio y la aplicación son bibliotecas .NET puras; game/ es el proyecto Godot.
set -euo pipefail
cd /work
mkdir -p .forjai
export NUGET_PACKAGES=/opt/nuget-packages
SLN=/tmp/Forjai.sln
make_sln() {
  dotnet new sln -n Forjai -o /tmp --force >/dev/null
  find /work -name '*.csproj' -not -path '*/.forjai/*' -print0 | xargs -0 -r dotnet sln "$SLN" add >/dev/null
}
case "$1" in
  restore) make_sln; dotnet restore "$SLN" --source /opt/nuget-packages ;;
  build)   make_sln; dotnet build "$SLN" --no-restore -c Debug ;;
  test)    make_sln; dotnet test "$SLN" --no-build -c Debug --logger "trx;LogFileName=results.trx" --results-directory /work/.forjai ;;
  smoke)
    [ -f /work/game/project.godot ] || { echo "Falta game/project.godot"; exit 1; }
    godot --headless --path /work/game --quit-after 300 > /tmp/godot.log 2>&1 || { cat /tmp/godot.log; exit 1; }
    cat /tmp/godot.log
    if grep -Eq "SCRIPT ERROR|ERROR:|Unhandled exception" /tmp/godot.log; then echo "Errores durante la ejecución"; exit 1; fi
    echo "300 frames sin errores" ;;
  *) echo "paso desconocido: $1"; exit 2 ;;
esac
```

- [ ] **Step 3: `sandbox/images/flutter-web-app/Dockerfile` y `run.sh`**

```dockerfile
FROM ghcr.io/cirruslabs/flutter:3.24.3
RUN apt-get update && apt-get install -y --no-install-recommends wget gnupg python3 ca-certificates \
    && wget -q -O /tmp/chrome.deb https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb \
    && apt-get install -y --no-install-recommends /tmp/chrome.deb \
    && rm -f /tmp/chrome.deb && rm -rf /var/lib/apt/lists/*
# Caché de pub con el catálogo inicial (sin paquetes externos más allá del SDK).
RUN flutter create --platforms=web /opt/seed && cd /opt/seed && flutter pub get && flutter precache --web \
    && rm -rf /opt/seed && chmod -R a+rX /sdks /root/.pub-cache 2>/dev/null || true
ENV PUB_CACHE=/root/.pub-cache
COPY run.sh /forjai/run.sh
RUN chmod 0755 /forjai/run.sh
```

```bash
#!/bin/bash
# Pasos fijos de FLUTTER_WEB_APP (solo web en esta ola).
set -euo pipefail
cd /work
mkdir -p .forjai
case "$1" in
  restore) flutter pub get --offline ;;
  build)   flutter build web --release ;;
  test)    flutter test --machine > /work/.forjai/results.json || true
           grep -q '"success":true' /work/.forjai/results.json ;;
  smoke)
    python3 -m http.server --directory /work/build/web 8080 --bind 127.0.0.1 > /tmp/http.log 2>&1 &
    sleep 2
    google-chrome --headless=new --no-sandbox --disable-gpu --enable-logging=stderr --v=0 \
      --virtual-time-budget=15000 --dump-dom http://127.0.0.1:8080/ > /tmp/dom.html 2> /tmp/chrome.log || true
    if grep -q "Uncaught" /tmp/chrome.log; then grep "Uncaught" /tmp/chrome.log; exit 1; fi
    if grep -Eq "flutter-view|flt-glass-pane" /tmp/dom.html; then echo "App Flutter presente en el DOM"; exit 0; fi
    echo "La app Flutter no apareció en el DOM"; exit 1 ;;
  *) echo "paso desconocido: $1"; exit 2 ;;
esac
```

- [ ] **Step 4: `sandbox/build-images.sh`**

```bash
#!/bin/bash
# Construye las imágenes del sandbox con Podman sin root (una vez y al cambiar versiones).
set -euo pipefail
cd "$(dirname "$0")/images"
for profile in dotnet-app godot-dotnet-game flutter-web-app; do
  echo "== $profile"
  podman build -t "localhost/forjai-sandbox/$profile:1" "$profile"
done
podman images | grep forjai-sandbox
```

- [ ] **Step 5: `sandbox-runner/Dockerfile` y `docker-compose.yml`**

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends podman git tar \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/sandbox-runner-0.1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

En `docker-compose.yml`, agregar el servicio (y en `company-core.environment`: `SANDBOX_RUNNER_URL: http://sandbox-runner:8090` y `SANDBOX_RUNNER_TOKEN: ${SANDBOX_RUNNER_TOKEN}`):

```yaml
  sandbox-runner:
    build:
      context: ./sandbox-runner
    container_name: forjai-sandbox-runner
    restart: unless-stopped
    user: "${UID:-1000}:${GID:-1000}"
    environment:
      SANDBOX_RUNNER_TOKEN: ${SANDBOX_RUNNER_TOKEN}
      SANDBOX_PRODUCTS_ROOT: ${HOME}/forjai-products
      SANDBOX_WORK_ROOT: ${HOME}/forjai-sandbox-work
      SANDBOX_PODMAN_URL: unix:///run/podman/podman.sock
      HOME: /tmp
    volumes:
      - ${HOME}/forjai-products:${HOME}/forjai-products:ro
      - ${HOME}/forjai-sandbox-work:${HOME}/forjai-sandbox-work
      - /run/user/${UID:-1000}/podman/podman.sock:/run/podman/podman.sock
    networks:
      - ai-company-net
```

`SANDBOX_RUNNER_TOKEN` va en `.env` (generar con `openssl rand -hex 32`; nunca commitearlo).

- [ ] **Step 6: Verificar** — `docker compose config -q` sin errores; `cd sandbox-runner && mvn -q test` en verde. (Construir las imágenes es parte de Task 9: requiere el socket de Podman del fundador y varios GB.)

- [ ] **Step 7: Commit** — `git add sandbox sandbox-runner/Dockerfile docker-compose.yml && git commit -m "Imágenes de sandbox por perfil, Dockerfile del runner y servicio en compose" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task 6: `company-core`: `SandboxRunnerClient`, `SandboxResult` y estado `VERIFIED`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/SandboxResult.java`
- Create: `app/src/main/java/com/aicompany/core/service/SandboxRunnerClient.java`
- Modify: `app/src/main/java/com/aicompany/core/model/StaticValidationStatus.java`
- Modify: `app/src/main/resources/application.yml`
- Test: `app/src/test/java/com/aicompany/core/service/SandboxRunnerClientTest.java`
- Test: `app/src/test/java/com/aicompany/core/model/StaticValidationStatusTest.java`

**Interfaces:**
- Produces: `record SandboxResult(String overall, List<StepResult> steps)` + `StepResult(...)` (mismo JSON que el runner), `int testsPassed()` (suma), `boolean passed()`; `SandboxRunnerClient(RestClient client, String token)` (el bean en `CoreConfig` arma el `RestClient` con URL y timeout) con `Optional<SandboxResult> verify(String missionId, String commitSha, String stackProfile)` y `String lastError()` (motivo si vacío); `StaticValidationStatus.VERIFIED` y `static StaticValidationStatus compute(List<StaticCheck> checks, StaticReviewResult review, SandboxResult sandbox)`.

- [ ] **Step 1: Tests (fallan)**

En `StaticValidationStatusTest` agregar:

```java
    private static SandboxResult sandbox(String overall, int passed) {
        return new SandboxResult(overall, List.of(
                new SandboxResult.StepResult("build", "PASS", 0, 1000, "", 0, 0),
                new SandboxResult.StepResult("test", overall, 0, 1000, "", passed, 0)));
    }

    @Test
    void verifiedWhenEverythingPassesWithAtLeastOneTest() {
        assertEquals(StaticValidationStatus.VERIFIED,
                StaticValidationStatus.compute(ALL_PASS, review("MINOR"), sandbox("PASS", 3)));
    }

    @Test
    void zeroTestsIsNotVerified() {
        assertEquals(StaticValidationStatus.FAILED,
                StaticValidationStatus.compute(ALL_PASS, review("MINOR"), sandbox("PASS", 0)));
    }

    @Test
    void aFailedSandboxFails() {
        assertEquals(StaticValidationStatus.FAILED,
                StaticValidationStatus.compute(ALL_PASS, review("MINOR"), sandbox("FAIL", 2)));
    }

    @Test
    void aMissingSandboxIsUnvalidated() {
        assertEquals(StaticValidationStatus.UNVALIDATED,
                StaticValidationStatus.compute(ALL_PASS, review("MINOR"), null));
    }
```

`SandboxRunnerClientTest` (con `MockRestServiceServer`, igual que `CeoServiceContextWindowTest`):

```java
package com.aicompany.core.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SandboxRunnerClientTest {

    private static final String RESULT = """
            {"overall":"PASS","steps":[{"name":"test","status":"PASS","exitCode":0,"durationMs":10,
            "outputTail":"","testsPassed":2,"testsFailed":0}]}""";

    @Test
    void sendsTheTokenAndParsesTheResult() {
        var builder = RestClient.builder().baseUrl("http://runner");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner/jobs"))
                .andExpect(header("X-Sandbox-Token", "t0k"))
                .andExpect(jsonPath("$.jobType").value("VERIFY"))
                .andExpect(jsonPath("$.stackProfile").value("DOTNET_APP"))
                .andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));

        var client = new SandboxRunnerClient(builder.build(), "t0k");
        var result = client.verify("M-1", "a".repeat(40), "DOTNET_APP");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().testsPassed());
        server.verify();
    }

    // Review Focus: runner caído o error → vacío con motivo, nunca excepción ni resultado inventado.
    @Test
    void aRunnerFailureIsEmptyWithAReason() {
        var builder = RestClient.builder().baseUrl("http://runner");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner/jobs")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var client = new SandboxRunnerClient(builder.build(), "t0k");

        assertTrue(client.verify("M-1", "a".repeat(40), "DOTNET_APP").isEmpty());
        assertNotNull(client.lastError());
    }
}
```

- [ ] **Step 2: Correr** — `cd app && mvn -q test -Dtest='SandboxRunnerClientTest,StaticValidationStatusTest'` → FAIL.

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.model;

import java.util.List;

/** Resultado del sandbox-runner (mismo JSON que sandbox-runner/SandboxResult). */
public record SandboxResult(String overall, List<StepResult> steps) {

    public record StepResult(String name, String status, int exitCode, long durationMs, String outputTail,
                             int testsPassed, int testsFailed) {
    }

    public List<StepResult> stepsOrEmpty() {
        return steps == null ? List.of() : steps;
    }

    public int testsPassed() {
        return stepsOrEmpty().stream().mapToInt(StepResult::testsPassed).sum();
    }

    public int testsFailed() {
        return stepsOrEmpty().stream().mapToInt(StepResult::testsFailed).sum();
    }

    public boolean passed() {
        return "PASS".equals(overall) && stepsOrEmpty().stream().allMatch(s -> "PASS".equals(s.status()));
    }
}
```

```java
package com.aicompany.core.service;

import com.aicompany.core.model.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * Cliente del sandbox-runner (spec 2026-09-26 §2). company-core nunca toca un motor de contenedores:
 * solo esta API. Cualquier falla → vacío + motivo (la misión queda UNVALIDATED, nunca VERIFIED).
 */
public class SandboxRunnerClient {

    private static final Logger log = LoggerFactory.getLogger(SandboxRunnerClient.class);

    private final RestClient client;
    private final String token;
    private volatile String lastError;

    public SandboxRunnerClient(RestClient client, String token) {
        this.client = client;
        this.token = token;
    }

    public Optional<SandboxResult> verify(String missionId, String commitSha, String stackProfile) {
        try {
            lastError = null;
            var result = client.post().uri("/jobs")
                    .header("X-Sandbox-Token", token == null ? "" : token)
                    .body(Map.of("jobType", "VERIFY", "missionId", missionId, "commitSha", commitSha,
                            "stackProfile", stackProfile))
                    .retrieve()
                    .body(SandboxResult.class);
            if (result == null) {
                lastError = "El sandbox-runner no devolvió resultado.";
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (Exception ex) {
            lastError = "sandbox-runner no disponible: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            log.warn("SANDBOX_UNAVAILABLE mission={} reason={}", missionId, lastError);
            return Optional.empty();
        }
    }

    public String lastError() {
        return lastError;
    }
}
```

Bean en `CoreConfig` (junto a `ollamaClient`):

```java
    @Bean
    com.aicompany.core.service.SandboxRunnerClient sandboxRunnerClient(
            @Value("${sandbox.runner.url}") String url,
            @Value("${sandbox.runner.token:}") String token,
            @Value("${sandbox.runner.timeout:30m}") java.time.Duration timeout) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(timeout);
        return new com.aicompany.core.service.SandboxRunnerClient(
                RestClient.builder().baseUrl(url).requestFactory(factory).build(), token);
    }
```

`application.yml`:

```yaml
sandbox:
  runner:
    url: ${SANDBOX_RUNNER_URL:http://localhost:8090}
    token: ${SANDBOX_RUNNER_TOKEN:}
    timeout: ${SANDBOX_RUNNER_TIMEOUT:30m}
```

`StaticValidationStatus`: agregar `VERIFIED` al enum y:

```java
    /**
     * Con sandbox (spec 2026-09-26 §4): FAILED si falla un chequeo, el sandbox,
     * hay BLOCKER/MAJOR o 0 tests pasados; UNVALIDATED si el sandbox no corrió o
     * no hubo revisión; VERIFIED si todo pasa con ≥1 test.
     */
    public static StaticValidationStatus compute(List<StaticCheck> checks, StaticReviewResult review, SandboxResult sandbox) {

        if (checks == null || checks.isEmpty() || checks.stream().anyMatch(c -> !c.passed())) {
            return FAILED;
        }

        if (sandbox != null && (!sandbox.passed() || sandbox.testsPassed() == 0)) {
            return FAILED;
        }

        var reviewOnly = compute(checks, review);
        if (reviewOnly == FAILED) {
            return FAILED;
        }

        if (sandbox == null || review == null) {
            return UNVALIDATED;
        }

        return VERIFIED;
    }
```

- [ ] **Step 4: Correr** — PASS; `cd app && mvn test` → BUILD SUCCESS.

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "company-core: cliente del sandbox-runner y estado VERIFIED" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task 7: Integración en `DevelopmentTeamStrategy`, persistencia y `ProductStatus.QA`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/AgentTask.java` (componente 14 `sandboxResult` + constructor de 13)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java` (`recordSandboxResult`, lectura en `tasks`)
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java`
- Modify: `app/src/main/java/com/aicompany/core/service/ProductStatusService.java`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`, `ProductStatusServiceTest.java`

**Interfaces:**
- Consumes: `SandboxRunnerClient.verify/lastError`, `StaticValidationStatus.compute(checks, review, sandbox)` (Task 6).
- Produces: constructor de `DevelopmentTeamStrategy` con `SandboxRunnerClient` como último parámetro; `MissionMemoryService.recordSandboxResult(String taskId, String sandboxJson)`; `AgentTask.sandboxResult()`.

- [ ] **Step 1: Tests (fallan)**

En `DevelopmentTeamStrategyTest`: agregar el campo `private final SandboxRunnerClient sandbox = mock(SandboxRunnerClient.class);`, pasar `sandbox` como último argumento del constructor, y en `stubHappyPath()` agregar:

```java
        when(sandbox.verify(eq("M-1"), anyString(), eq("GODOT_DOTNET_GAME"))).thenReturn(Optional.of(
                new SandboxResult("PASS", List.of(
                        new SandboxResult.StepResult("build", "PASS", 0, 48000, "", 0, 0),
                        new SandboxResult.StepResult("test", "PASS", 0, 12000, "", 12, 0),
                        new SandboxResult.StepResult("smoke", "PASS", 0, 9000, "", 0, 0)))));
```

Tests nuevos:

```java
    @Test
    void aPassingSandboxAndCleanReviewIsVerified() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("VERIFIED"), anyString());
        verify(memory).recordSandboxResult(eq("M-1-QA"), anyString());
        assertTrue(result.verifiableState().contains("Tests PASS 12/12"));
        assertTrue(result.verifiableState().contains("Compiló, pasaron 12 tests y arrancó en el sandbox."));
    }

    @Test
    void theReviewReceivesTheRealSandboxResults() throws Exception {
        stubHappyPath();
        var reviewPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.review(anyString(), anyString(), anyString(), reviewPrompt.capture(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        assertTrue(reviewPrompt.getValue().contains("RESULTADOS REALES DEL SANDBOX"));
    }

    // Review Focus: runner no disponible → UNVALIDATED con el motivo.
    @Test
    void anUnavailableRunnerLeavesTheWorkUnvalidated() throws Exception {
        stubHappyPath();
        when(sandbox.verify(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(sandbox.lastError()).thenReturn("sandbox-runner no disponible: Connection refused");
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        verify(memory).recordStaticValidation(eq("M-1-QA"), eq("UNVALIDATED"), anyString());
        assertTrue(result.verifiableState().contains("Connection refused"));
    }

    // Review Focus: si los chequeos deterministas fallaron, no se gasta tiempo compilando.
    @Test
    void failedDeterministicChecksSkipTheSandbox() throws Exception {
        stubHappyPath();
        when(validator.validate(eq("M-1"), anyList(), any(), anyList(), anyList()))
                .thenReturn(List.of(StaticCheck.fail("DDD_LAYERS", "violación", null, List.of())));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        verifyNoInteractions(sandbox);
    }
```

En `ProductStatusServiceTest`:

```java
    @Test
    void aVerifiedValidationMeansQa() {
        var qa = new AgentTask("MISSION-9-QA", "MISSION-9", "qa", "STATIC_REVIEW", "COMPLETED", "{}",
                Instant.parse("2026-09-26T00:00:00Z"), "VALIDATION", null, null, null, "VERIFIED", "[]");
        when(missionMemory.tasks("MISSION-9")).thenReturn(List.of(qa));
        when(customerMemory.totalRevenueAndCost("MISSION-9")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-9")).thenReturn(0L);

        assertEquals(ProductStatus.QA, service.resolve("MISSION-9"));
    }
```

- [ ] **Step 2: Correr** — FAIL (constructor, `recordSandboxResult`, estado).

- [ ] **Step 3: Implementar**

1. `AgentTask`: agregar `String sandboxResult` como componente 14; agregar un constructor de 13 argumentos que delega con `sandboxResult=null` (el de 7 sigue delegando con `null`s).
2. `MissionMemoryService`:

```java
    /** Resultado real del sandbox, en la tarea VALIDATION (spec 2026-09-26 §4). */
    public void recordSandboxResult(String taskId, String sandboxJson) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (t:AgentTask {id:$id}) SET t.sandboxResult=$sandbox, t.updatedAt=$updatedAt",
                        Map.of("id", taskId, "sandbox", sandboxJson, "updatedAt", Instant.now().toString()));
                return null;
            });
        }
    }
```

y en `tasks()`: `t.sandboxResult AS sandboxResult` en el `RETURN` y `nullableString(r.get("sandboxResult"))` como último argumento.

3. `DevelopmentTeamStrategy`:
   - Campo y parámetro de constructor `SandboxRunnerClient sandbox` (último).
   - Justo después de `var checks = staticValidator.validate(...)`:

```java
        SandboxResult sandboxResult = null;
        String sandboxError = null;
        var headSha = committed.get(committed.size() - 1).commitSha();

        if (checks.stream().allMatch(StaticCheck::passed) && profile != null) {
            progress.advance(MissionStatus.EVALUATING, 70, "Sandbox",
                    "Compilando, corriendo tests y arrancando el código en el sandbox.");
            var verified = sandbox.verify(missionId, headSha, profile.name());
            sandboxResult = verified.orElse(null);
            sandboxError = verified.isEmpty() ? sandbox.lastError() : null;
        } else {
            sandboxError = "Sandbox omitido: los chequeos deterministas fallaron.";
        }

        if (sandboxResult != null) {
            memory.recordSandboxResult(validationTaskId, toJson(sandboxResult));
            memory.recordEvidence(validationTaskId, missionId, "sandbox", sandboxEvidence(missionId, headSha, sandboxResult));
            events.publish("EMPRESA_SANDBOX_VERIFICATION_COMPLETED", missionId, validationTaskId, "sandbox",
                    Map.of("overall", sandboxResult.overall(), "testsPassed", sandboxResult.testsPassed()));
        }
```

   - Dentro del `try` de la revisión, reemplazar `var headSha = committed.get(committed.size() - 1).commitSha();` por el `headSha` ya declarado, y pasar `sandboxResult`/`sandboxError` a `buildReviewPrompt` (nuevo bloque en el prompt):

```
                RESULTADOS REALES DEL SANDBOX (ejecución real; no los contradigas):
                %s
```

con el texto de `sandboxSummary(sandboxResult, sandboxError)`.
   - `var status = StaticValidationStatus.compute(checks, review, sandboxResult);`
   - Helpers:

```java
    private static String sandboxSummary(SandboxResult result, String error) {
        if (result == null) {
            return "No corrió: " + error;
        }
        return result.stepsOrEmpty().stream()
                .map(s -> {
                    var label = switch (s.name()) {
                        case "build" -> "Build";
                        case "test" -> "Tests";
                        case "smoke" -> "Arranque";
                        default -> s.name();
                    };
                    var tests = "test".equals(s.name())
                            ? " " + s.testsPassed() + "/" + (s.testsPassed() + s.testsFailed()) : "";
                    return label + " " + s.status() + tests + " (" + (s.durationMs() / 1000) + " s)";
                })
                .collect(Collectors.joining(" · "));
    }

    private static List<AgentResult.Evidence> sandboxEvidence(String missionId, String sha, SandboxResult result) {
        return result.stepsOrEmpty().stream()
                .map(s -> new AgentResult.Evidence(s.name() + " " + s.status() + " en el sandbox",
                        "sandbox:" + missionId + "@" + sha + "/" + s.name(), "INTERNAL", true))
                .toList();
    }
```

   - `verifiableState`: agregar la línea `out.append("Sandbox: ").append(sandboxSummary(sandboxResult, sandboxError)).append("\n");` (pasando ambos a `verifiableState`), y reemplazar el cierre fijo por:

```java
        if (status == StaticValidationStatus.VERIFIED) {
            out.append("Compiló, pasaron ").append(sandboxResult.testsPassed())
                    .append(" tests y arrancó en el sandbox. No garantiza que el producto esté completo ni que no tenga errores fuera de lo probado.");
        } else {
            out.append(NO_EXECUTION_DISCLAIMER);
        }
```

     y si algún paso falló, antes del cierre:

```java
        if (sandboxResult != null) {
            sandboxResult.stepsOrEmpty().stream()
                    .filter(s -> "FAIL".equals(s.status()) || "TIMEOUT".equals(s.status()))
                    .findFirst()
                    .ifPresent(s -> {
                        var output = s.outputTail() == null ? "" : s.outputTail();
                        out.append("Paso fallido: ").append(s.name()).append("\n")
                                .append(output.substring(Math.max(0, output.length() - 1500))).append("\n");
                    });
        }
```
   - Imports: `SandboxResult`, `AgentResult`.

   Ajustar el texto de `NO_EXECUTION_DISCLAIMER` a: `"No se verificó la ejecución: no se puede afirmar que el producto compile, se ejecute o pase tests."` (el test existente que busca la frase vieja se actualiza a la nueva).

4. `ProductStatusService.isQaValidated`:

```java
    /** QA = la validación quedó VERIFIED: compiló, pasó tests y arrancó en el sandbox (spec 2026-09-26 §4). */
    private boolean isQaValidated(String missionId) {
        return missionMemory.tasks(missionId).stream()
                .anyMatch(t -> "VALIDATION".equals(t.kind()) && "VERIFIED".equals(t.validationStatus()));
    }
```

- [ ] **Step 4: Correr** — `cd app && mvn test` → BUILD SUCCESS.

- [ ] **Step 5: Commit** — `git add app/src && git commit -m "Sandbox integrado en la misión: VERIFY tras los chequeos, evidencia real, VERIFIED y ProductStatus.QA" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task 8: Frontend — pasos del sandbox en el detalle de misión

**Files:**
- Modify: `app/frontend/src/api/types.ts`, `app/frontend/src/pages/MissionDetailPage.tsx`, `app/frontend/src/statusColor.ts`

- [ ] **Step 1: `types.ts`** — en `AgentTask` agregar `sandboxResult: string | null` y:

```ts
export interface SandboxStep {
  name: string
  status: 'PASS' | 'FAIL' | 'TIMEOUT' | 'SKIPPED'
  exitCode: number
  durationMs: number
  outputTail: string
  testsPassed: number
  testsFailed: number
}

export interface SandboxResult {
  overall: 'PASS' | 'FAIL'
  steps: SandboxStep[]
}
```

- [ ] **Step 2: `MissionDetailPage.tsx`** — helper `parseSandbox(raw: string | null): SandboxResult | null` (mismo patrón que `parseChecks`) y, dentro de la sección "Validación estática" de cada tarea con `validationStatus`, agregar:

```tsx
            {parseSandbox(task.sandboxResult) && (
              <>
                <h3>Sandbox</h3>
                <ul>
                  {parseSandbox(task.sandboxResult)!.steps.map((step) => (
                    <li key={step.name}>
                      {step.status === 'PASS' ? '🟢' : step.status === 'SKIPPED' ? '⚪' : '🔴'} {step.name}: {step.status} (
                      {Math.round(step.durationMs / 1000)} s)
                      {step.name === 'test' && ` — ${step.testsPassed}/${step.testsPassed + step.testsFailed} tests`}
                      {(step.status === 'FAIL' || step.status === 'TIMEOUT') && step.outputTail && (
                        <pre className="mission-message">{step.outputTail.slice(-1500)}</pre>
                      )}
                    </li>
                  ))}
                </ul>
              </>
            )}
```

- [ ] **Step 3: `statusColor.ts`** — agregar `'VERIFIED'` a `GREEN`.

- [ ] **Step 4: Verificar** — `cd app/frontend && npm run lint && npm run build` → en verde.

- [ ] **Step 5: Commit** — `git add app/frontend/src && git commit -m "Command Center: pasos del sandbox y estado VERIFIED en el detalle de misión" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

### Task 9: Documentación, preparación y verificación en vivo

- [ ] **Step 1: Preparación (requiere al fundador para el socket)**

```bash
systemctl --user is-active podman.socket        # debe decir active (lo activa el fundador)
mkdir -p ~/forjai-sandbox-work
grep -q SANDBOX_RUNNER_TOKEN .env || echo "SANDBOX_RUNNER_TOKEN=$(openssl rand -hex 32)" >> .env
bash sandbox/build-images.sh                    # varios GB; anotar tiempos y tamaños
```

Si una imagen no construye (URL, versión, paquete), corregir el `Dockerfile` con el cambio mínimo y anotarlo como ruling.

- [ ] **Step 2: Prueba de humo del runner sin company-core** — construir a mano un repo mínimo `.NET` (proyecto `src/Demo.Api` con `/health`, `tests/Demo.Tests` con un test) en `~/forjai-products/SANDBOX-SMOKE`, commitearlo, levantar `docker compose up -d sandbox-runner` y llamar:

```bash
curl -s -X POST http://localhost:8090/jobs ...   # desde dentro de la red: docker exec ai-company-core curl ...
```

(con el token y `stackProfile=DOTNET_APP`). Esperado: los 5 pasos en `PASS` y `testsPassed ≥ 1`. Luego romper el test y confirmar `FAIL` en `test`. Borrar `SANDBOX-SMOKE` al terminar.

- [ ] **Step 3: Misiones reales** — confirmar que no hay misiones en curso, `docker compose build && docker compose up -d`, y lanzar una misión `TEAM-ENGINEERING` por perfil (`DOTNET_APP`, `GODOT_DOTNET_GAME`, `FLUTTER_WEB_APP`) con instrucciones que empujen cada perfil. Registrar por misión: pasos del sandbox, tests, `validationStatus`, y el error real del primer paso fallido.

- [ ] **Step 4: Docs** — `CLAUDE.md` (sección sandbox: runner, aislamiento, `VERIFIED`, preparación), `docs/EVENTS.md` (`EMPRESA_SANDBOX_VERIFICATION_COMPLETED`), `docs/HISTORY.md` (decisiones, bugs reales, salida real).

- [ ] **Step 5: Commit** — `git add CLAUDE.md docs && git commit -m "Documentar el sandbox-runner y registrar la verificación en vivo" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"`

---

## Self-review

- **Cobertura del spec**: §2 runner (API síncrona, token, sin comandos, extracción del commit, aislamiento, timeouts, prueba de arranque por perfil, `SandboxResult`, imágenes) → Tasks 1-5; §4 integración (orden, `VERIFIED`, 0 tests → `FAILED`, runner caído → `UNVALIDATED`, evidencia `sandbox:<mission>@<sha>/<paso>`, estado verificable, evento, frontend, `ProductStatus.QA`) → Tasks 6-8; verificación en vivo → Task 9. §3 (dependencias gobernadas) queda para la parte 3: las imágenes traen un catálogo inicial precargado.
- **Decisión de alcance a confirmar en la revisión**: las plantillas de exportación de Godot no se incluyen (la prueba de arranque corre el proyecto sin exportar); el `.sln` lo genera el script del paso, no se usa el que escribe el modelo.
- **Tipos**: `SandboxResult`/`StepResult` con los mismos nombres de campo en runner y `company-core` (JSON compatible); `SandboxRunnerClient.verify(missionId, commitSha, stackProfile)` en Tasks 6-7; `StaticValidationStatus.compute(checks, review, sandbox)` en Tasks 6-7.

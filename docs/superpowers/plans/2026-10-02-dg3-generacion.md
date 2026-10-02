# DG3 — Código omitido y entrega por partes: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que un agente de desarrollo no pueda entregar código con partes omitidas (`...`, "resto del código") y que una capa grande no falle por una respuesta cortada: el agente entrega en lotes y Java los junta.

**Architecture:** `ElidedCodeGate` (Java, puro) se suma a `DevelopmentRuntime.verifyGenerated` como error con reintento. El corte se detecta de forma determinista (`finish_reason`/`done_reason` = `length`, o JSON terminado a mitad) y `CeoService.callStructured` lo señala con `TruncatedResponseException`. `DevelopmentRuntime` deja de hacer una sola llamada por intento: pide lotes (`complete`, `remainingPaths`) hasta `complete = true`, con la lista de rutas ya recibidas en cada continuación; un lote cortado se repite pidiendo menos archivos.

**Tech Stack:** Java 21, Spring Boot 4.1 (`RestClient`), Jackson 3, JUnit 5 + Mockito, `MockRestServiceServer`.

**Spec:** `docs/superpowers/specs/2026-10-01-development-group-fase1-design.md` §5. Hoja de ruta: `docs/superpowers/plans/2026-10-01-development-group-fase1-roadmap.md` (bloque 3).

## Global Constraints

- Jackson 3 (`tools.jackson.*`). Sin `@Async`. Nunca combinar `format` y `tools` en la misma llamada.
- Determinismo sobre LLM: qué es código omitido y qué es una respuesta cortada lo decide Java.
- El código omitido se reintenta (no falla la tarea de inmediato); el motivo exacto va en `CORRECCIÓN DEL INTENTO ANTERIOR`, como el resto de los rechazos de `DevelopmentRuntime`.
- Compatibilidad: una respuesta sin `complete` cuenta como completa (planes y tests viejos siguen igual).
- Mensajes para el modelo y errores en español.

## Review Focus

1. **Código legítimo con `...`** (spread de Dart `...items`, rangos, strings con "..."): no debe rechazarse. Test en Task 1 (`legitimateEllipsisIsNotElidedCode`).
2. **`NotImplementedException` en un archivo de tests** (un test que verifica que algo lanza): permitido. Test en Task 1 (`notImplementedInsideTestsIsAllowed`).
3. **El modelo marca `complete=false` pero el lote siguiente no trae rutas nuevas** (bucle infinito o repetición): falla el intento con el motivo, no gira para siempre. Test en Task 3 (`aBatchWithoutNewPathsEndsTheAttempt`).
4. **Un lote que se corta dos veces seguidas** aun pidiendo menos archivos: falla el intento con un motivo claro. Test en Task 3 (`twoCutsInARowEndTheAttempt`).
5. **Un archivo devuelto en dos lotes**: gana la última versión, sin duplicados. Test en Task 3 (`aFileSentTwiceKeepsTheLastVersion`).

---

### Task 1: `ElidedCodeGate`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ElidedCodeGate.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java` (`verifyGenerated`, junto a `ProjectFileGate.check`)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/ElidedCodeGateTest.java`, `app/src/test/java/com/aicompany/core/agent/DevelopmentRuntimeTest.java`

**Interfaces:**
- Produces: `static List<String> ElidedCodeGate.check(List<DevelopmentResult.GeneratedFile> files)` — un mensaje por archivo con código omitido, con ruta, número de línea y la línea.

- [ ] **Step 1: Write the failing test**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verificado en vivo (MISSION-1790796941181): 72 errores de compilación por archivos con "..." en lugar de código. */
class ElidedCodeGateTest {

    private static List<String> check(String path, String content) {
        return ElidedCodeGate.check(List.of(new GeneratedFile(path, content)));
    }

    @Test
    void aLineWithOnlyAnEllipsisIsElidedCode() {
        var errors = check("src/Citas.Application/Reservar.cs", "public class Reservar {\n    ...\n}");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("src/Citas.Application/Reservar.cs") && errors.get(0).contains("línea 2"),
                errors.toString());
    }

    @Test
    void commentsThatSkipCodeAreElidedCode() {
        assertFalse(check("lib/a.dart", "void main() {\n  // ...\n}").isEmpty());
        assertFalse(check("lib/a.dart", "void main() {\n  // resto del código igual\n}").isEmpty());
        assertFalse(check("src/A.cs", "class A {\n  /* rest of the code */\n}").isEmpty());
        assertFalse(check("src/A.cs", "class A {\n  // TODO: implementar\n}").isEmpty());
    }

    @Test
    void notImplementedOutsideTestsIsElidedCode() {
        assertFalse(check("src/A.cs", "class A { void B() => throw new NotImplementedException(); }").isEmpty());
        assertFalse(check("lib/a.dart", "void b() => throw UnimplementedError();").isEmpty());
    }

    @Test
    void notImplementedInsideTestsIsAllowed() {
        assertTrue(check("tests/Citas.Tests/ReservarTests.cs",
                "Assert.Throws<NotImplementedException>(() => throw new NotImplementedException());").isEmpty());
        assertTrue(check("test/saludo/saludo_test.dart", "expect(() => throw UnimplementedError(), throwsA(anything));")
                .isEmpty());
    }

    @Test
    void legitimateEllipsisIsNotElidedCode() {
        assertTrue(check("lib/a.dart", "final all = [...items, ...more];\nfinal s = 'Cargando...';").isEmpty());
        assertTrue(check("src/A.cs", "var r = list[1..^1];\nvar t = \"Espere...\";").isEmpty());
    }

    @Test
    void nonCodeFilesAreNotChecked() {
        assertTrue(check("README.md", "Instalar...\n...\n").isEmpty());
        assertTrue(check("pubspec.yaml", "# ...\nname: app").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=ElidedCodeGateTest`
Expected: FAIL de compilación (`ElidedCodeGate` no existe).

- [ ] **Step 3: Implement**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Código omitido (spec 2026-10-01 §5): el modelo resume en vez de escribir ("...", "// resto del código",
 * "TODO: implementar", NotImplementedException). Verificado en vivo (MISSION-1790796941181): 72 errores de
 * compilación por esto. Solo mira archivos de código y solo líneas que son SOLO la elisión o un comentario,
 * para no confundir el spread de Dart, rangos de C# o textos con "...". Puro: nunca llama a un modelo.
 */
public final class ElidedCodeGate {

    private static final Set<String> CODE_EXTENSIONS =
            Set.of(".cs", ".dart", ".js", ".ts", ".tsx", ".jsx", ".py", ".java", ".gd", ".html", ".css");

    private static final Pattern ONLY_ELLIPSIS = Pattern.compile("^(\\.{3}|…)$");
    private static final Pattern COMMENTED_ELLIPSIS =
            Pattern.compile("^(//+|#|/\\*+|\\*|<!--)\\s*(\\.{3}|…)\\s*(\\*/|-->)?$");
    private static final Pattern COMMENT = Pattern.compile("^(//+|#|/\\*+|\\*|<!--).*");
    private static final List<String> SKIP_PHRASES = List.of(
            "resto del código", "resto del codigo", "el resto igual", "código existente", "codigo existente",
            "rest of the code", "rest of code", "existing code", "todo: implement");
    private static final Pattern NOT_IMPLEMENTED = Pattern.compile("NotImplementedException\\s*\\(|UnimplementedError\\s*\\(");

    private ElidedCodeGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files) {
        var errors = new ArrayList<String>();
        for (var file : files.stream().filter(Objects::nonNull).toList()) {
            var path = file.path() == null ? "" : file.path();
            if (!isCode(path) || file.content() == null) {
                continue;
            }
            var lines = file.content().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                var line = lines[i].strip();
                if (isElided(line) || (!isTest(path) && NOT_IMPLEMENTED.matcher(line).find())) {
                    errors.add("Código omitido en " + path + " (línea " + (i + 1) + ": \"" + abbreviate(line) + "\"). "
                            + "Escribe el archivo COMPLETO, sin \"...\", sin \"resto del código\" y sin métodos sin "
                            + "implementar: el sandbox compila exactamente lo que entregas.");
                    break;
                }
            }
        }
        return errors;
    }

    private static boolean isElided(String line) {
        if (ONLY_ELLIPSIS.matcher(line).matches() || COMMENTED_ELLIPSIS.matcher(line).matches()) {
            return true;
        }
        if (!COMMENT.matcher(line).matches()) {
            return false;
        }
        var lower = line.toLowerCase(Locale.ROOT);
        return SKIP_PHRASES.stream().anyMatch(lower::contains);
    }

    private static boolean isCode(String path) {
        var lower = path.toLowerCase(Locale.ROOT);
        return CODE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static boolean isTest(String path) {
        return path.startsWith("test/") || path.startsWith("tests/") || path.contains(".Tests/")
                || path.endsWith("_test.dart") || path.endsWith("Tests.cs");
    }

    private static String abbreviate(String line) {
        return line.length() <= 80 ? line : line.substring(0, 80) + "…";
    }
}
```

En `DevelopmentRuntime.verifyGenerated`, después de `retryable.addAll(ProjectFileGate.check(...));`:

```java
        // Spec 2026-10-01 §5: código omitido ("...", "resto del código") → reintento con la línea exacta.
        retryable.addAll(ElidedCodeGate.check(result.files()));
```

(import `com.aicompany.core.agent.validation.ElidedCodeGate`).

Agregar a `DevelopmentRuntimeTest`:

```java
    @Test
    void elidedCodeIsRetriedWithTheExactLine() throws Exception {
        when(ceoService.generateDevelopmentArtifact(eq("backend"), anyString(), anyString(), anyString()))
                .thenReturn(new DevelopmentResult("r", List.of(new GeneratedFile("web/game/main.js", "function a() {\n  // ...\n}"))))
                .thenReturn(dev("web/game/main.js"));

        runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ceoService, times(2)).generateDevelopmentArtifact(eq("backend"), prompts.capture(), anyString(), anyString());
        assertTrue(prompts.getAllValues().get(1).contains("Código omitido en web/game/main.js (línea 2"),
                prompts.getAllValues().get(1));
    }
```

- [ ] **Step 4: Run tests**

Run: `cd app && mvn -q test -Dtest='ElidedCodeGateTest,DevelopmentRuntimeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/ElidedCodeGate.java app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java app/src/test/java/com/aicompany/core/agent/validation/ElidedCodeGateTest.java app/src/test/java/com/aicompany/core/agent/DevelopmentRuntimeTest.java
git commit -m "Development Group: el código omitido se rechaza con reintento (ElidedCodeGate)"
```

---

### Task 2: Detectar una respuesta cortada

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/TruncatedResponseException.java`
- Modify: `app/src/main/java/com/aicompany/core/service/OpenAiCompatibleClient.java` (`RemoteReply` + `finish_reason`)
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java` (`ModelMessage`, rama remota, rama Ollama, `callStructured`)
- Test: `OpenAiCompatibleClientTest`, `CeoServiceRemoteModelTest`

**Interfaces:**
- Produces: `OpenAiCompatibleClient.RemoteReply(String content, List<Map<String,Object>> toolCalls, String finishReason)` con constructor de 2 argumentos (`finishReason = null`). `TruncatedResponseException extends IllegalStateException` (constructor `(String message)`). `CeoService.generateDevelopmentArtifact` lanza `TruncatedResponseException` si el proveedor marcó `length` o si el JSON terminó a mitad (`Unexpected end-of-input`).

- [ ] **Step 1: Write the failing tests**

En `OpenAiCompatibleClientTest`:

```java
    @Test
    void theFinishReasonIsReturned() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions")).andRespond(withSuccess("""
                {"choices":[{"message":{"role":"assistant","content":"{\\"summary\\":\\"s\\",\\"fi"},
                "finish_reason":"length"}]}""", MediaType.APPLICATION_JSON));
        var client = new OpenAiCompatibleClient(builder.build(), "k3y", Duration.ZERO);

        var reply = client.complete("m", MESSAGES, null, true, 100);

        assertEquals("length", reply.finishReason());
    }
```

En `CeoServiceRemoteModelTest`:

```java
    @Test
    void aDevelopmentArtifactCutByTheTokenLimitIsReportedAsTruncated() {
        when(remote.complete(eq("moonshotai/kimi-k3"), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"summary\":\"s\",\"files\":[{\"path\":\"a\",\"con",
                        List.of(), "length"));

        assertThrows(TruncatedResponseException.class,
                () -> ceoService.generateDevelopmentArtifact("frontend-ui", "p", "", "nvidia:moonshotai/kimi-k3"));
    }

    @Test
    void aJsonThatEndsMidwayIsTruncatedEvenWithoutAFinishReason() {
        when(remote.complete(eq("moonshotai/kimi-k3"), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"summary\":\"s\",\"files\":[{\"pa", List.of()));

        assertThrows(TruncatedResponseException.class,
                () -> ceoService.generateDevelopmentArtifact("frontend-ui", "p", "", "nvidia:moonshotai/kimi-k3"));
    }

    @Test
    void anInvalidButCompleteJsonIsNotTruncated() {
        when(remote.complete(eq("moonshotai/kimi-k3"), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("no es json", List.of(), "stop"));

        var ex = assertThrows(IllegalStateException.class,
                () -> ceoService.generateDevelopmentArtifact("frontend-ui", "p", "", "nvidia:moonshotai/kimi-k3"));
        assertFalse(ex instanceof TruncatedResponseException);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='OpenAiCompatibleClientTest,CeoServiceRemoteModelTest'`
Expected: FAIL de compilación (`finishReason`, `TruncatedResponseException`).

- [ ] **Step 3: Implement**

`TruncatedResponseException.java`:

```java
package com.aicompany.core.service;

/**
 * La respuesta del modelo se cortó (límite de tokens de salida): el proveedor marcó "length" o el JSON terminó
 * a mitad. Spec 2026-10-01 §5: quien llama pide la continuación en lotes más chicos en vez de descartarla.
 */
public class TruncatedResponseException extends IllegalStateException {

    public TruncatedResponseException(String message) {
        super(message);
    }
}
```

`OpenAiCompatibleClient`:

```java
    public record RemoteReply(String content, List<Map<String, Object>> toolCalls, String finishReason) {
        public RemoteReply(String content, List<Map<String, Object>> toolCalls) {
            this(content, toolCalls, null);
        }
    }
```

y en `complete`, al armar la respuesta:

```java
                var finishReason = choices.get(0).get("finish_reason");
                return new RemoteReply(content == null ? "" : String.valueOf(content), toOllamaToolCalls(rawCalls),
                        finishReason == null ? null : String.valueOf(finishReason));
```

`CeoService`:
- `ModelMessage` gana `boolean truncated` con constructor de 2 argumentos (`false`):

```java
    private record ModelMessage(String content, List<Map<String, Object>> toolCalls, boolean truncated) {
        ModelMessage(String content, List<Map<String, Object>> toolCalls) {
            this(content, toolCalls, false);
        }
    }
```

- Rama remota: `return new ModelMessage(reply.content(), reply.toolCalls(), "length".equals(reply.finishReason()));`
- Rama Ollama (al final): `return new ModelMessage(content, toolCalls, "length".equals(String.valueOf(response.get("done_reason"))));`
- `callStructured`: la llamada pasa a

```java
            var message = callModel(operation, agentId, model, messages, schema, null, false);
            response = message.content();
            if (message.truncated()) {
                throw new TruncatedResponseException("La respuesta de " + agentId + " para " + operation
                        + " se cortó por el límite de salida (" + response.length() + " caracteres).");
            }
```

y en el `catch (Exception ex)`, antes del `throw new IllegalStateException(...)` existente:

```java
            if (ex instanceof TruncatedResponseException truncated) {
                throw truncated;
            }
            if (String.valueOf(ex.getMessage()).contains("end-of-input")) {
                throw new TruncatedResponseException("La respuesta de " + agentId + " para " + operation
                        + " terminó a mitad del JSON (" + (response == null ? 0 : response.length()) + " caracteres).");
            }
```

(el `log.error` existente queda antes, para no perder el registro).

- [ ] **Step 4: Run tests**

Run: `cd app && mvn -q test -Dtest='OpenAiCompatibleClientTest,CeoServiceRemoteModelTest,CeoServiceTeamCallsTest,CeoServiceModelHealthTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/TruncatedResponseException.java app/src/main/java/com/aicompany/core/service/OpenAiCompatibleClient.java app/src/main/java/com/aicompany/core/service/CeoService.java app/src/test/java/com/aicompany/core/service/OpenAiCompatibleClientTest.java app/src/test/java/com/aicompany/core/service/CeoServiceRemoteModelTest.java
git commit -m "Development Group: una respuesta cortada por el límite de salida se detecta (finish_reason/done_reason o JSON a mitad)"
```

---

### Task 3: Entrega por lotes en `DevelopmentRuntime`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/model/DevelopmentResult.java` (`complete`, `remainingPaths`)
- Modify: `app/src/main/java/com/aicompany/core/agent/model/DevelopmentResultSchema.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java` (`generate` usa `collectBatches`)
- Test: `app/src/test/java/com/aicompany/core/agent/DevelopmentRuntimeTest.java`

**Interfaces:**
- Consumes: `TruncatedResponseException` (Task 2).
- Produces: `DevelopmentResult(String summary, List<GeneratedFile> files, List<PackageRequest> packages, Boolean complete, List<String> remainingPaths)`; constructores de 2 y 3 argumentos se conservan (`complete = null`, `remainingPaths = []`); `boolean isComplete()` (`null` → `true`); `List<String> remainingPathsOrEmpty()`. `DevelopmentRuntime.MAX_BATCHES = 8`, `DevelopmentRuntime.BATCH_RULE` (texto que se agrega al prompt).

- [ ] **Step 1: Write the failing tests** (en `DevelopmentRuntimeTest`)

```java
    private static DevelopmentResult part(boolean complete, List<String> remaining, String... paths) {
        return new DevelopmentResult("lote", java.util.Arrays.stream(paths)
                .map(p -> new GeneratedFile(p, "contenido de " + p)).toList(), List.of(), complete, remaining);
    }

    @Test
    void batchesAreJoinedUntilComplete() throws Exception {
        when(ceoService.generateDevelopmentArtifact(eq("frontend-ui"), anyString(), anyString(), anyString()))
                .thenReturn(part(false, List.of("web/game/b.js"), "web/game/a.js"))
                .thenReturn(part(true, List.of(), "web/game/b.js"));

        var result = runtime.generate("T-1", "MISSION-1", "frontend-ui", "prompt", List.of("web/game")).get();

        assertEquals(List.of("web/game/a.js", "web/game/b.js"), result.files().stream().map(GeneratedFile::path).toList());
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ceoService, times(2)).generateDevelopmentArtifact(eq("frontend-ui"), prompts.capture(), anyString(), anyString());
        assertTrue(prompts.getAllValues().get(1).contains("YA RECIBIDOS: [web/game/a.js]"), prompts.getAllValues().get(1));
        assertTrue(prompts.getAllValues().get(1).contains("web/game/b.js"), prompts.getAllValues().get(1));
    }

    @Test
    void aCutBatchIsRequestedAgainWithFewerFiles() throws Exception {
        when(ceoService.generateDevelopmentArtifact(eq("frontend-ui"), anyString(), anyString(), anyString()))
                .thenThrow(new com.aicompany.core.service.TruncatedResponseException("se cortó"))
                .thenReturn(part(true, List.of(), "web/game/a.js"));

        runtime.generate("T-1", "MISSION-1", "frontend-ui", "prompt", List.of("web/game")).get();

        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ceoService, times(2)).generateDevelopmentArtifact(eq("frontend-ui"), prompts.capture(), anyString(), anyString());
        assertTrue(prompts.getAllValues().get(1).contains("SE CORTÓ"), prompts.getAllValues().get(1));
    }

    @Test
    void twoCutsInARowEndTheAttempt() {
        when(ceoService.generateDevelopmentArtifact(eq("frontend-ui"), anyString(), anyString(), anyString()))
                .thenThrow(new com.aicompany.core.service.TruncatedResponseException("se cortó"));

        var future = runtime.generate("T-1", "MISSION-1", "frontend-ui", "prompt", List.of("web/game"));

        var ex = assertThrows(java.util.concurrent.ExecutionException.class, future::get);
        assertTrue(ex.getCause().getMessage().contains("se cortó dos veces"), ex.getCause().getMessage());
    }

    @Test
    void aBatchWithoutNewPathsEndsTheAttempt() {
        when(ceoService.generateDevelopmentArtifact(eq("frontend-ui"), anyString(), anyString(), anyString()))
                .thenReturn(part(false, List.of("web/game/b.js"), "web/game/a.js"));

        var future = runtime.generate("T-1", "MISSION-1", "frontend-ui", "prompt", List.of("web/game"));

        var ex = assertThrows(java.util.concurrent.ExecutionException.class, future::get);
        assertTrue(ex.getCause().getMessage().contains("no trajo archivos nuevos"), ex.getCause().getMessage());
    }

    @Test
    void aFileSentTwiceKeepsTheLastVersion() throws Exception {
        when(ceoService.generateDevelopmentArtifact(eq("frontend-ui"), anyString(), anyString(), anyString()))
                .thenReturn(part(false, List.of("web/game/b.js"), "web/game/a.js"))
                .thenReturn(new DevelopmentResult("lote", List.of(new GeneratedFile("web/game/a.js", "versión 2"),
                        new GeneratedFile("web/game/b.js", "b")), List.of(), true, List.of()));

        var result = runtime.generate("T-1", "MISSION-1", "frontend-ui", "prompt", List.of("web/game")).get();

        assertEquals(2, result.files().size());
        assertEquals("versión 2", result.files().get(0).content());
    }

    @Test
    void theBatchRuleIsAlwaysInThePrompt() throws Exception {
        when(ceoService.generateDevelopmentArtifact(eq("backend"), anyString(), anyString(), anyString()))
                .thenReturn(dev("web/game/main.js"));

        runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        verify(ceoService).generateDevelopmentArtifact(eq("backend"), contains("ENTREGA POR LOTES"), anyString(), anyString());
    }
```

(Nota: `twoCutsInARowEndTheAttempt` y `aBatchWithoutNewPathsEndsTheAttempt` agotan los 3 intentos de `executeWithRetries`; el mensaje final incluye el motivo del último.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest=DevelopmentRuntimeTest`
Expected: FAIL de compilación (constructor de 5 argumentos de `DevelopmentResult`).

- [ ] **Step 3: Implement**

`DevelopmentResult`:

```java
public record DevelopmentResult(
        String summary,
        List<GeneratedFile> files,
        List<PackageRequest> packages,
        Boolean complete,
        List<String> remainingPaths
) {
    public DevelopmentResult(String summary, List<GeneratedFile> files) {
        this(summary, files, List.of(), null, List.of());
    }

    public DevelopmentResult(String summary, List<GeneratedFile> files, List<PackageRequest> packages) {
        this(summary, files, packages, null, List.of());
    }

    /** Spec 2026-10-01 §5: sin el campo, la respuesta cuenta como completa (compatibilidad). */
    public boolean isComplete() {
        return complete == null || complete;
    }

    public List<String> remainingPathsOrEmpty() {
        return remainingPaths == null ? List.of() : remainingPaths;
    }
    // ... packagesOrEmpty, GeneratedFile y PackageRequest sin cambios
}
```

Buscar con `grep -rn "new DevelopmentResult(" app/src` los usos de 3 argumentos: siguen compilando por el constructor de 3. `@JsonInclude` no aplica (Jackson lee los campos ausentes como `null`).

`DevelopmentResultSchema.SCHEMA`: agregar a `properties`:

```java
                    "complete", Map.of("type", "boolean"),
                    "remainingPaths", Map.of("type", "array", "items", Map.of("type", "string"))
```

y a `required`: `"complete"`.

`DevelopmentRuntime`:

```java
    static final int MAX_BATCHES = 8;

    static final String BATCH_RULE = """

            ENTREGA POR LOTES (obligatorio): tu respuesta tiene un límite de salida. Entrega como máximo 4 archivos o
            unos 30.000 caracteres por respuesta, cada archivo COMPLETO. Si te faltan archivos, responde con
            "complete": false y "remainingPaths" con las rutas que todavía vas a entregar; Forjai te pedirá el resto.
            Cuando ya entregaste todo, "complete": true y "remainingPaths": [].
            """;

    /**
     * Spec 2026-10-01 §5 (verificado en vivo: MISSION-1790905978528, el JSON de Mila se cortó en los 3 intentos).
     * Junta lotes hasta complete=true. Un lote cortado se pide de nuevo con menos archivos; dos cortes seguidos, un
     * lote sin rutas nuevas o más de MAX_BATCHES lotes terminan el intento (executeWithRetries reintenta).
     */
    DevelopmentResult collectBatches(String agentId, String prompt, String agentPrompt, String model) {
        var byPath = new java.util.LinkedHashMap<String, DevelopmentResult.GeneratedFile>();
        var packages = new ArrayList<DevelopmentResult.PackageRequest>();
        String summary = null;
        String continuation = "";
        var cutInARow = 0;

        for (int batch = 1; batch <= MAX_BATCHES; batch++) {
            DevelopmentResult part;
            try {
                part = ceoService.generateDevelopmentArtifact(agentId, prompt + BATCH_RULE + continuation, agentPrompt, model);
                cutInARow = 0;
            } catch (com.aicompany.core.service.TruncatedResponseException ex) {
                cutInARow++;
                if (cutInARow >= 2) {
                    throw new IllegalStateException("La respuesta se cortó dos veces seguidas aun pidiendo menos "
                            + "archivos: entrega de a 1 o 2 archivos por lote.");
                }
                continuation = "\n\nTU RESPUESTA ANTERIOR SE CORTÓ por el límite de salida y se descartó: entrega como "
                        + "máximo 2 archivos en este lote." + received(byPath.keySet());
                continue;
            }

            if (summary == null) {
                summary = part.summary();
            }
            var newPaths = 0;
            for (var file : part.files() == null ? List.<DevelopmentResult.GeneratedFile>of() : part.files()) {
                if (file == null || file.path() == null) {
                    continue;
                }
                if (!byPath.containsKey(file.path())) {
                    newPaths++;
                }
                byPath.put(file.path(), file);
            }
            packages.addAll(part.packagesOrEmpty());

            if (part.isComplete()) {
                return new DevelopmentResult(summary, new ArrayList<>(byPath.values()), packages, true, List.of());
            }
            if (newPaths == 0) {
                throw new IllegalStateException("El lote " + batch + " no trajo archivos nuevos y dijo complete=false: "
                        + "entrega las rutas que faltan o responde complete=true.");
            }
            continuation = "\n\nCONTINUACIÓN (lote " + (batch + 1) + "): no repitas lo ya entregado." + received(byPath.keySet())
                    + "\nTe faltan, según tu lote anterior: " + part.remainingPathsOrEmpty();
        }

        throw new IllegalStateException("Superaste " + MAX_BATCHES + " lotes sin marcar complete=true.");
    }

    private static String received(java.util.Set<String> paths) {
        return paths.isEmpty() ? "" : "\nARCHIVOS YA RECIBIDOS: " + new ArrayList<>(paths);
    }
```

En `generate(..., requiredPaths)`, reemplazar `ceoService.generateDevelopmentArtifact(agentId, attemptPrompt, agentPrompt, model)` por `collectBatches(agentId, attemptPrompt, agentPrompt, model)`.

El texto de la continuación debe contener literalmente `YA RECIBIDOS: [web/game/a.js]` (el test lo busca): `"ARCHIVOS YA RECIBIDOS: " + new ArrayList<>(paths)` produce `ARCHIVOS YA RECIBIDOS: [web/game/a.js]`.

- [ ] **Step 4: Run tests**

Run: `cd app && mvn -q test -Dtest='DevelopmentRuntimeTest,DevelopmentTeamStrategyTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/model/DevelopmentResult.java app/src/main/java/com/aicompany/core/agent/model/DevelopmentResultSchema.java app/src/main/java/com/aicompany/core/agent/DevelopmentRuntime.java app/src/test/java/com/aicompany/core/agent/DevelopmentRuntimeTest.java
git commit -m "Development Group: entrega por lotes; un lote cortado se pide de nuevo con menos archivos"
```

---

### Task 4: Suite completa y documentación

**Files:**
- Modify: `CLAUDE.md` (sección del Development Group: `DevelopmentRuntime`)

- [ ] **Step 1: Suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Documentación**

En `CLAUDE.md`, en el paréntesis de `DevelopmentRuntime` (`..`/`.git` → falla sin reintento; …), agregar: "código omitido (`ElidedCodeGate`: línea `...`, comentarios 'resto del código'/`TODO: implementar`, `NotImplementedException`/`UnimplementedError` fuera de tests) → reintento con la línea; entrega por lotes (`complete`/`remainingPaths`, máx. 4 archivos o ~30.000 caracteres por lote, hasta 8 lotes): una respuesta cortada (`finish_reason`/`done_reason` = `length` o JSON a mitad → `TruncatedResponseException`) se pide de nuevo con menos archivos; dos cortes seguidos o un lote sin rutas nuevas terminan el intento".

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "Docs: Development Group bloque 3 (código omitido y entrega por lotes)"
```

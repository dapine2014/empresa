# CEO en NVIDIA NIM con fallback a Ollama — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el CEO (consolidación de misión + chat general) use NVIDIA NIM (`openai/gpt-oss-20b`) como proveedor de LLM, con fallback automático y auditable a Ollama si NVIDIA falla, devuelve una respuesta vacía, o se agota un presupuesto diario de cortesía — sin tocar nunca el camino de los agentes (`sales`/`product`/`finance`/`engineering`/`qa`), que siguen 100% en Ollama local.

**Architecture:** Nueva abstracción `LlmProvider` (paquete `com.aicompany.core.llm`) con dos implementaciones — `OllamaLlmProvider` (extrae la llamada RestClient a Ollama que hoy vive en `CeoService.callModel`, acotada a las llamadas del CEO) y `NvidiaNimLlmProvider` (cliente OpenAI-compatible nuevo, con la normalización de formato de `tool_calls` en ambos sentidos). `CeoService` recibe el proveedor configurado (`ceoProvider`, seleccionado por Spring según `llm.ceo-provider`) más `OllamaLlmProvider` como respaldo fijo, y envuelve las únicas dos llamadas del CEO (`chat`, `executeMission`) en un método nuevo `callCeo(...)` que aplica el presupuesto diario (`AiBudgetService`) y el fallback. `executeAgentTask` no cambia — sigue llamando a Ollama directo vía el `callModel` privado existente.

**Tech Stack:** Java 21, Spring Boot 4.1.1, `RestClient`, Jackson 3 (`tools.jackson.*`), JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-18-nvidia-ceo-provider-design.md`

## Global Constraints

- `executeAgentTask` (los 5 agentes delegados) **no cambia** — sigue llamando a Ollama directo vía el `callModel` privado ya existente en `CeoService`, con `format`/`think` como hoy. Ninguna tarea de este plan lo toca.
- Default `llm.ceo-provider=ollama` — nadie activa NVIDIA sin setear la variable explícitamente. Si se agrega la variable a `docker-compose.yml`, debe usar la sintaxis `${VAR:-default}` de Docker Compose (no `${VAR}` a secas) para no pisar ese default con una cadena vacía cuando `.env` no la define — Spring interpreta una variable de entorno presente-pero-vacía como "sí presente", así que su propio `${LLM_CEO_PROVIDER:ollama}` no aplicaría el default si Docker ya puso `""`.
- `format` y `tools` nunca se combinan en la misma llamada — esa regla (`rejectFormatCombinedWithTools`) es exclusiva del `callModel` de agentes y no se toca; `LlmProvider.chat` ni siquiera acepta un parámetro `format`.
- La respuesta de NVIDIA trae `function.arguments` como string JSON serializado (no un `Map` ya decodificado, como sí hace Ollama) — `NvidiaNimLlmProvider` decodifica al recibir y vuelve a codificar al reenviar un mensaje `assistant` con `tool_calls` (el segundo turno de `chat()` construye ese mensaje con `arguments` como `Map`, pensado originalmente para Ollama).
- Un `content` nulo/vacío en la respuesta del proveedor CEO **sin** `tool_calls** cuenta como fallo (el caso de truncamiento real observado en el spike con `gpt-oss-20b`) y dispara fallback a Ollama — un `content` vacío CON `tool_calls` es un turno de herramienta normal, no un fallo.
- Cada fallback publica `EMPRESA_CEO_PROVIDER_FALLBACK` (`data: {operation, reason}`, `reason` ∈ `{RATE_LIMIT_OR_ERROR, BUDGET_EXHAUSTED}`) vía `CompanyEventPublisher` — evento distinto de las alertas por correo existentes.
- `AiBudgetService`: contador `AtomicInteger`, resetea al cambiar la fecha calendario (comparación simple, sin scheduler), **no persiste entre restarts** (mismo criterio ya documentado para `Agent.status`/`MissionExecutor`). Solo cuenta llamadas cuando el proveedor configurado es NVIDIA — nunca gatea Ollama.
- Sin test de integración real contra NVIDIA ni contra Ollama (mismo criterio ya establecido en el proyecto) — cada provider expone su lógica de parseo/normalización en un método package-private testeable con fixtures, sin red ni RestClient real (mismo patrón que `SerperSearchAdapter.parseResults`).
- No se agrega ninguna dependencia nueva a `pom.xml` — `RestClient` y `JsonMapper` ya están disponibles.
- `mvn test` debe quedar en verde después de cada tarea.

---

### Task 1: `LlmProvider`/`LlmResponse` (paquete nuevo) + reemplazar `ModelMessage` en `CeoService`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/llm/LlmProvider.java`
- Create: `app/src/main/java/com/aicompany/core/llm/LlmResponse.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`

**Interfaces:**
- Produces: `com.aicompany.core.llm.LlmResponse(String content, List<Map<String,Object>> toolCalls)`; `com.aicompany.core.llm.LlmProvider.chat(String operation, List<Map<String,Object>> messages, List<Map<String,Object>> tools) -> LlmResponse` — ambos usados por las Tasks 2, 3 y 5.
- Consumes: nada nuevo — este task es un refactor puro (renombrar un record privado existente por uno público estructuralmente igual), sin cambiar ningún comportamiento observable.

Este task es solo mecánico: no cambia ningún test existente, `mvn test` debe seguir dando exactamente el mismo resultado que antes de empezar.

- [ ] **Step 1: Crear `LlmResponse.java`**

```java
package com.aicompany.core.llm;

import java.util.List;
import java.util.Map;

/**
 * Respuesta ya normalizada de un turno de chat con el CEO, sin importar
 * qué {@link LlmProvider} la generó (Ollama o NVIDIA NIM). Estructuralmente
 * igual al record privado {@code ModelMessage} que antes vivía dentro de
 * {@code CeoService} — ahora público para que ambos proveedores concretos
 * puedan devolverlo sin que el resto de {@code CeoService} necesite saber
 * cuál respondió.
 */
public record LlmResponse(String content, List<Map<String, Object>> toolCalls) {
}
```

- [ ] **Step 2: Crear `LlmProvider.java`**

```java
package com.aicompany.core.llm;

import java.util.List;
import java.util.Map;

/**
 * Abstracción exclusiva del CEO (consolidación de misión + chat general,
 * incluyendo el tool-calling de {@code query_company_memory}) — los
 * agentes delegados (`sales`/`product`/`finance`/`engineering`/`qa`, vía
 * {@code CeoService.executeAgentTask}) siguen llamando a Ollama directo,
 * sin pasar por esta interfaz: el diseño aprobado es "CEO potente,
 * workers locales", no una abstracción total del cliente LLM del
 * proyecto. Deliberadamente sin parámetro `format` (los agentes son los
 * únicos que piden un `AgentResult` con JSON Schema) ni `think`
 * (exclusivo del turno de decisión de herramienta de los agentes) — el
 * CEO nunca usó ninguno de los dos.
 */
public interface LlmProvider {

    LlmResponse chat(
            String operation,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools);
}
```

- [ ] **Step 3: Reemplazar el record privado `ModelMessage` de `CeoService` por `LlmResponse`**

En `app/src/main/java/com/aicompany/core/service/CeoService.java`, agregar el import (junto a los demás imports de `com.aicompany.core.*`):

```java
import com.aicompany.core.llm.LlmResponse;
```

Reemplazar la firma del overload de 6 argumentos:

```java
    @SuppressWarnings("unchecked")
    private ModelMessage callModel(
            String operation,
            String actor,
            String model,
            List<Map<String, Object>> messages,
            Object format,
            List<Map<String, Object>> tools) {
```

por:

```java
    @SuppressWarnings("unchecked")
    private LlmResponse callModel(
            String operation,
            String actor,
            String model,
            List<Map<String, Object>> messages,
            Object format,
            List<Map<String, Object>> tools) {
```

Reemplazar la firma del overload de 7 argumentos:

```java
    private ModelMessage callModel(
            String operation,
            String actor,
            String model,
            List<Map<String, Object>> messages,
            Object format,
            List<Map<String, Object>> tools,
            Boolean think) {
```

por:

```java
    private LlmResponse callModel(
            String operation,
            String actor,
            String model,
            List<Map<String, Object>> messages,
            Object format,
            List<Map<String, Object>> tools,
            Boolean think) {
```

Dentro de ese mismo método (7 argumentos), reemplazar los 3 `return new ModelMessage(...)`:

```java
            return new ModelMessage("Sin respuesta del modelo.", List.of());
```

(aparece dos veces, una cuando `response == null` y otra cuando `msg == null`) por:

```java
            return new LlmResponse("Sin respuesta del modelo.", List.of());
```

y al final del método:

```java
        return new ModelMessage(content, toolCalls);
```

por:

```java
        return new LlmResponse(content, toolCalls);
```

Eliminar por completo el record privado que ya no se usa:

```java
    private record ModelMessage(
            String content,
            List<Map<String, Object>> toolCalls
    ) {
    }
```

- [ ] **Step 4: Verificar que compila y que la suite sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, mismo número de tests que antes de este task (209), 0 fallos — este task no agrega ni quita ningún test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/llm/LlmProvider.java \
        app/src/main/java/com/aicompany/core/llm/LlmResponse.java \
        app/src/main/java/com/aicompany/core/service/CeoService.java
git commit -m "Extraer LlmResponse/LlmProvider; reemplazar ModelMessage privado de CeoService"
```

---

### Task 2: `OllamaLlmProvider`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/llm/OllamaLlmProvider.java`
- Create: `app/src/main/java/com/aicompany/core/config/LlmConfig.java`
- Create: `app/src/test/java/com/aicompany/core/llm/OllamaLlmProviderTest.java`

**Interfaces:**
- Consumes: `com.aicompany.core.llm.LlmProvider`/`LlmResponse` (Task 1).
- Produces: `OllamaLlmProvider(RestClient ollama, String model)`, implementa `LlmProvider.chat(...)`; método package-private `LlmResponse parseResponse(Map<String,Object> response)` (testeable sin red); bean Spring `ollamaLlmProvider` en `LlmConfig` — usado por la Task 5 (`ceoProvider`/`ollamaFallbackProvider`).

- [ ] **Step 1: Escribir el test de `parseResponse` (falla primero: la clase no existe)**

Crear `app/src/test/java/com/aicompany/core/llm/OllamaLlmProviderTest.java`:

```java
package com.aicompany.core.llm;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OllamaLlmProviderTest {

    private final OllamaLlmProvider provider =
            new OllamaLlmProvider(mock(RestClient.class), "qwen2.5-coder:14b");

    @Test
    void parsesContentAndToolCallsFromOllamaResponse() {
        var response = Map.<String, Object>of(
                "message", Map.of(
                        "content", "hola",
                        "tool_calls", List.of(Map.of(
                                "function", Map.of("name", "query_company_memory")
                        ))
                )
        );

        var result = provider.parseResponse(response);

        assertEquals("hola", result.content());
        assertEquals(1, result.toolCalls().size());
    }

    @Test
    void returnsPlaceholderWhenResponseIsNull() {
        var result = provider.parseResponse(null);

        assertEquals("Sin respuesta del modelo.", result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void returnsPlaceholderWhenMessageIsMissing() {
        var result = provider.parseResponse(Map.of());

        assertEquals("Sin respuesta del modelo.", result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void returnsEmptyToolCallsWhenAbsent() {
        var response = Map.<String, Object>of(
                "message", Map.of("content", "sin herramientas")
        );

        var result = provider.parseResponse(response);

        assertEquals("sin herramientas", result.content());
        assertTrue(result.toolCalls().isEmpty());
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla (la clase no existe todavía)**

Run: `cd app && mvn test -Dtest=OllamaLlmProviderTest`
Expected: FAIL — no se encuentra el símbolo `OllamaLlmProvider`.

- [ ] **Step 3: Crear `OllamaLlmProvider.java`**

```java
package com.aicompany.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Envuelve exactamente la llamada RestClient a Ollama (`POST /api/chat`)
 * que antes vivía en {@code CeoService.callModel}, acotada a las llamadas
 * del CEO: nunca pasa `format` (exclusivo del `AgentResult` de los
 * agentes) ni `think` (exclusivo del turno de decisión de herramienta de
 * los agentes) — el CEO nunca usó ninguno de los dos. `tools` sí se
 * soporta: {@code CeoService.chat} lo usa para `query_company_memory`.
 */
public class OllamaLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmProvider.class);

    private final RestClient ollama;
    private final String model;

    public OllamaLlmProvider(RestClient ollama, String model) {
        this.ollama = ollama;
        this.model = model;
    }

    @Override
    public LlmResponse chat(
            String operation,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {

        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        Map<String, Object> response;

        try {

            response = ollama
                    .post()
                    .uri("/api/chat")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

        } catch (Exception ex) {

            log.error("OLLAMA_ERROR operation={} model={}", operation, model, ex);
            throw ex;
        }

        return parseResponse(response);
    }

    /**
     * Sin modificador de acceso a propósito: permite que
     * {@code OllamaLlmProviderTest} verifique el parseo sin RestClient
     * real, mismo patrón que {@code SerperSearchAdapter.parseResults}.
     */
    @SuppressWarnings("unchecked")
    LlmResponse parseResponse(Map<String, Object> response) {

        if (response == null) {
            return new LlmResponse("Sin respuesta del modelo.", List.of());
        }

        var msg = (Map<String, Object>) response.get("message");

        if (msg == null) {
            return new LlmResponse("Sin respuesta del modelo.", List.of());
        }

        var content = String.valueOf(msg.get("content"));
        var rawToolCalls = msg.get("tool_calls");

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        if (rawToolCalls instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof Map<?, ?> map) {
                    toolCalls.add((Map<String, Object>) map);
                }
            }
        }

        return new LlmResponse(content, toolCalls);
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `cd app && mvn test -Dtest=OllamaLlmProviderTest`
Expected: PASS, 4/4.

- [ ] **Step 5: Crear `LlmConfig.java` con el bean de `OllamaLlmProvider`**

```java
package com.aicompany.core.config;

import com.aicompany.core.llm.OllamaLlmProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Selecciona qué {@code LlmProvider} usa el CEO
 * (`llm.ceo-provider=ollama|nvidia`, default `ollama` — ver
 * `docs/superpowers/specs/2026-09-18-nvidia-ceo-provider-design.md`).
 * Completado en la Task 5 de ese plan con el bean `NvidiaNimLlmProvider`
 * y el bean selector `ceoProvider`.
 */
@Configuration
public class LlmConfig {

    @Bean
    OllamaLlmProvider ollamaLlmProvider(
            RestClient ollama,
            @Value("${ollama.ceo-model}") String ceoModel) {

        return new OllamaLlmProvider(ollama, ceoModel);
    }
}
```

- [ ] **Step 6: Compilar todo el proyecto y confirmar que la suite sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 213/213 (209 + 4 nuevos de `OllamaLlmProviderTest`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/llm/OllamaLlmProvider.java \
        app/src/main/java/com/aicompany/core/config/LlmConfig.java \
        app/src/test/java/com/aicompany/core/llm/OllamaLlmProviderTest.java
git commit -m "Agregar OllamaLlmProvider (extraido de CeoService.callModel)"
```

---

### Task 3: `NvidiaNimLlmProvider`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/llm/NvidiaNimLlmProvider.java`
- Create: `app/src/test/java/com/aicompany/core/llm/NvidiaNimLlmProviderTest.java`

**Interfaces:**
- Consumes: `com.aicompany.core.llm.LlmProvider`/`LlmResponse` (Task 1).
- Produces: `NvidiaNimLlmProvider(String baseUrl, String apiKey, String model, int maxTokens, JsonMapper jsonMapper)`, implementa `LlmProvider.chat(...)`; métodos package-private `LlmResponse parseResponse(String raw)` y `List<Map<String,Object>> normalizeOutgoingMessages(List<Map<String,Object>> messages)` (ambos testeables sin red) — usados por la Task 5 (`ceoProvider` cuando `llm.ceo-provider=nvidia`).

- [ ] **Step 1: Escribir el test (falla primero: la clase no existe)**

Crear `app/src/test/java/com/aicompany/core/llm/NvidiaNimLlmProviderTest.java`:

```java
package com.aicompany.core.llm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NvidiaNimLlmProviderTest {

    private final NvidiaNimLlmProvider provider = new NvidiaNimLlmProvider(
            "https://integrate.api.nvidia.com/v1",
            "fake-api-key",
            "openai/gpt-oss-20b",
            8192,
            JsonMapper.builder().build()
    );

    @Test
    void parsesPlainTextResponse() {
        var raw = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "Estado actual de Forjai."
                      }
                    }
                  ]
                }
                """;

        var result = provider.parseResponse(raw);

        assertEquals("Estado actual de Forjai.", result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void decodesToolCallArgumentsFromJsonString() {
        var raw = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [
                          {
                            "function": {
                              "name": "query_company_memory",
                              "arguments": "{\\"topic\\": \\"COMPANY_STATUS\\"}"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        var result = provider.parseResponse(raw);

        assertNull(result.content());
        assertEquals(1, result.toolCalls().size());

        @SuppressWarnings("unchecked")
        var function = (Map<String, Object>) result.toolCalls().get(0).get("function");
        assertEquals("query_company_memory", function.get("name"));

        @SuppressWarnings("unchecked")
        var arguments = (Map<String, Object>) function.get("arguments");
        assertEquals("COMPANY_STATUS", arguments.get("topic"));
    }

    @Test
    void returnsNullContentWhenTruncated() {
        var raw = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null
                      },
                      "finish_reason": "length"
                    }
                  ]
                }
                """;

        var result = provider.parseResponse(raw);

        assertNull(result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void returnsEmptyResponseWhenNoChoices() {
        var result = provider.parseResponse("{\"choices\": []}");

        assertNull(result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void normalizesOutgoingToolCallArgumentsFromMapToJsonString() {
        var messages = List.<Map<String, Object>>of(
                Map.of(
                        "role", "assistant",
                        "content", "",
                        "tool_calls", List.of(Map.of(
                                "function", Map.of(
                                        "name", "query_company_memory",
                                        "arguments", Map.of("topic", "COMPANY_STATUS")
                                )
                        ))
                )
        );

        var normalized = provider.normalizeOutgoingMessages(messages);

        @SuppressWarnings("unchecked")
        var toolCalls = (List<Map<String, Object>>) normalized.get(0).get("tool_calls");
        @SuppressWarnings("unchecked")
        var function = (Map<String, Object>) toolCalls.get(0).get("function");

        assertInstanceOf(String.class, function.get("arguments"));
        assertTrue(((String) function.get("arguments")).contains("COMPANY_STATUS"));
    }

    @Test
    void leavesMessagesWithoutToolCallsUnchanged() {
        var messages = List.<Map<String, Object>>of(
                Map.of("role", "user", "content", "hola")
        );

        var normalized = provider.normalizeOutgoingMessages(messages);

        assertEquals(messages, normalized);
    }

    @Test
    void rejectsCallWhenApiKeyMissing() {
        var withoutKey = new NvidiaNimLlmProvider(
                "https://integrate.api.nvidia.com/v1", "", "openai/gpt-oss-20b", 8192,
                JsonMapper.builder().build()
        );

        var ex = assertThrows(IllegalStateException.class,
                () -> withoutKey.chat(
                        "CEO_CHAT",
                        List.of(Map.of("role", "user", "content", "hola")),
                        null
                ));

        assertTrue(ex.getMessage().contains("NVIDIA_API_KEY"));
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=NvidiaNimLlmProviderTest`
Expected: FAIL — no se encuentra el símbolo `NvidiaNimLlmProvider`.

- [ ] **Step 3: Crear `NvidiaNimLlmProvider.java`**

```java
package com.aicompany.core.llm;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente OpenAI-compatible de NVIDIA NIM
 * (`https://integrate.api.nvidia.com/v1/chat/completions`), usado solo
 * para el CEO (ver {@link LlmProvider}). Dos diferencias de formato
 * respecto a Ollama que este cliente normaliza para que el resto de
 * {@code CeoService} no necesite saber qué proveedor respondió:
 *
 * <ol>
 *   <li>La respuesta trae {@code function.arguments} como un string JSON
 *       serializado, no un objeto ya decodificado (como sí hace Ollama)
 *       — se decodifica acá antes de construir el {@link LlmResponse}
 *       ({@link #parseResponse}).</li>
 *   <li>En sentido inverso, un mensaje "assistant" de `tool_calls` que
 *       {@code CeoService} vuelve a mandar en el segundo turno (con
 *       `arguments` como {@code Map}, formato que sí acepta Ollama)
 *       necesita re-serializarse a string JSON antes de mandarse a NVIDIA
 *       ({@link #normalizeOutgoingMessages}) — si no, la API lo rechaza.</li>
 * </ol>
 *
 * Mismo criterio que {@code SerperSearchAdapter}: construye su propio
 * {@code RestClient} internamente (no inyectado) y valida la API key al
 * momento de la llamada, no al construirse — así el bean existe siempre,
 * sin condicionales de arranque, y solo falla si de verdad se intenta
 * usar sin key configurada.
 */
public class NvidiaNimLlmProvider implements LlmProvider {

    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final JsonMapper jsonMapper;

    public NvidiaNimLlmProvider(
            String baseUrl,
            String apiKey,
            String model,
            int maxTokens,
            JsonMapper jsonMapper) {

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .build();

        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public LlmResponse chat(
            String operation,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("NVIDIA_API_KEY no está configurada");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("stream", false);
        body.put("max_tokens", maxTokens);
        body.put("messages", normalizeOutgoingMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        var raw = client
                .post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(String.class);

        return parseResponse(raw);
    }

    /**
     * Sin modificador de acceso a propósito: permite que
     * {@code NvidiaNimLlmProviderTest} verifique la normalización sin
     * red real.
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> normalizeOutgoingMessages(List<Map<String, Object>> messages) {

        var normalized = new ArrayList<Map<String, Object>>();

        for (var message : messages) {

            var rawToolCalls = message.get("tool_calls");

            if (!(rawToolCalls instanceof List<?> toolCallsList) || toolCallsList.isEmpty()) {
                normalized.add(message);
                continue;
            }

            var normalizedToolCalls = new ArrayList<Map<String, Object>>();

            for (var item : toolCallsList) {

                if (!(item instanceof Map<?, ?> toolCall)) {
                    continue;
                }

                var function = (Map<String, Object>) toolCall.get("function");
                var arguments = function == null ? null : function.get("arguments");

                if (arguments instanceof Map<?, ?>) {

                    var normalizedFunction = new LinkedHashMap<>(function);
                    normalizedFunction.put("arguments", jsonMapper.writeValueAsString(arguments));

                    var normalizedToolCall = new LinkedHashMap<String, Object>((Map<String, Object>) toolCall);
                    normalizedToolCall.put("function", normalizedFunction);

                    normalizedToolCalls.add(normalizedToolCall);

                } else {
                    normalizedToolCalls.add((Map<String, Object>) toolCall);
                }
            }

            var normalizedMessage = new LinkedHashMap<>(message);
            normalizedMessage.put("tool_calls", normalizedToolCalls);
            normalized.add(normalizedMessage);
        }

        return normalized;
    }

    /**
     * Sin modificador de acceso a propósito: permite que
     * {@code NvidiaNimLlmProviderTest} verifique el parseo con fixtures
     * JSON, sin red real (mismo patrón que
     * {@code SerperSearchAdapter.parseResults}).
     *
     * Parsea la forma OpenAI-compatible de NVIDIA NIM:
     * {@code choices[0].message.content} (puede venir JSON `null` — el
     * truncamiento real observado en el spike con `gpt-oss-20b` y
     * `max_tokens` insuficiente, ver el spec) y
     * {@code choices[0].message.tool_calls[].function.arguments} como
     * string JSON serializado, decodificado acá a `Map`.
     */
    @SuppressWarnings("unchecked")
    LlmResponse parseResponse(String raw) {

        var root = jsonMapper.readTree(raw);
        var choices = root.path("choices");

        if (!choices.isArray() || choices.isEmpty()) {
            return new LlmResponse(null, List.of());
        }

        var message = choices.get(0).path("message");
        var contentNode = message.path("content");
        var content = contentNode.isNull() ? null : contentNode.asString(null);

        var toolCallsNode = message.path("tool_calls");
        var toolCalls = new ArrayList<Map<String, Object>>();

        if (toolCallsNode.isArray()) {

            for (var toolCallNode : toolCallsNode) {

                var functionNode = toolCallNode.path("function");
                var name = functionNode.path("name").asString(null);
                var argumentsRaw = functionNode.path("arguments").asString(null);

                Map<String, Object> arguments;

                try {
                    arguments = argumentsRaw == null
                            ? Map.of()
                            : jsonMapper.readValue(argumentsRaw, Map.class);
                } catch (Exception ex) {
                    arguments = Map.of();
                }

                toolCalls.add(Map.of(
                        "function", Map.of(
                                "name", name == null ? "" : name,
                                "arguments", arguments
                        )
                ));
            }
        }

        return new LlmResponse(content, toolCalls);
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `cd app && mvn test -Dtest=NvidiaNimLlmProviderTest`
Expected: PASS, 7/7.

- [ ] **Step 5: Compilar todo el proyecto y confirmar que la suite sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 220/220 (213 + 7 nuevos).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/llm/NvidiaNimLlmProvider.java \
        app/src/test/java/com/aicompany/core/llm/NvidiaNimLlmProviderTest.java
git commit -m "Agregar NvidiaNimLlmProvider (cliente OpenAI-compatible de NVIDIA NIM)"
```

---

### Task 4: `AiBudgetService`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/AiBudgetService.java`
- Create: `app/src/test/java/com/aicompany/core/service/AiBudgetServiceTest.java`
- Modify: `app/src/main/java/com/aicompany/core/config/CoreConfig.java` (agregar bean `Clock`)

**Interfaces:**
- Produces: `AiBudgetService(Clock clock, int dailyLimit)`, `boolean isExhausted()`, `void recordCall()` — usados por la Task 5 (`CeoService.callCeo`).

- [ ] **Step 1: Escribir el test (falla primero: la clase no existe)**

Crear `app/src/test/java/com/aicompany/core/service/AiBudgetServiceTest.java`:

```java
package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reloj fake mutable, deliberadamente no {@code Clock.fixed(...)} —
 * `resetsWhenCalendarDateChanges` necesita avanzar el reloj sobre la
 * MISMA instancia de {@code AiBudgetService} para probar el reset por
 * cambio de fecha (ver spec: "inyectando un reloj fake, no
 * Instant.now() real").
 */
class AiBudgetServiceTest {

    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void isNotExhaustedBelowLimit() {
        var clock = new MutableClock(Instant.parse("2026-09-19T10:00:00Z"));
        var budget = new AiBudgetService(clock, 3);

        budget.recordCall();
        budget.recordCall();

        assertFalse(budget.isExhausted());
    }

    @Test
    void isExhaustedAtLimit() {
        var clock = new MutableClock(Instant.parse("2026-09-19T10:00:00Z"));
        var budget = new AiBudgetService(clock, 2);

        budget.recordCall();
        budget.recordCall();

        assertTrue(budget.isExhausted());
    }

    @Test
    void resetsWhenCalendarDateChanges() {
        var clock = new MutableClock(Instant.parse("2026-09-19T23:59:00Z"));
        var budget = new AiBudgetService(clock, 1);

        budget.recordCall();
        assertTrue(budget.isExhausted());

        clock.advance(Instant.parse("2026-09-20T00:01:00Z"));

        assertFalse(budget.isExhausted());
    }

    @Test
    void neverExhaustedWhenLimitIsZeroOrLessIsNotAssumed_startsAtZeroCalls() {
        var clock = new MutableClock(Instant.parse("2026-09-19T10:00:00Z"));
        var budget = new AiBudgetService(clock, 5);

        assertFalse(budget.isExhausted());
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=AiBudgetServiceTest`
Expected: FAIL — no se encuentra el símbolo `AiBudgetService`.

- [ ] **Step 3: Crear `AiBudgetService.java`**

```java
package com.aicompany.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tope de cortesía sobre el tier gratuito de evaluación de NVIDIA NIM —
 * ver "Presupuesto diario simple, en memoria" en
 * `docs/superpowers/specs/2026-09-18-nvidia-ceo-provider-design.md`. No
 * persiste entre restarts a propósito (mismo criterio ya documentado en
 * `CLAUDE.md` para `Agent.status`/`MissionExecutor`): es un límite de
 * cortesía, no un control de gasto real, ya que este tier no cobra nada.
 * Solo lo consulta/incrementa {@code CeoService.callCeo} cuando el
 * proveedor configurado del CEO es NVIDIA — nunca gatea Ollama.
 */
@Service
public class AiBudgetService {

    private final Clock clock;
    private final int dailyLimit;
    private final AtomicInteger callsToday = new AtomicInteger(0);
    private volatile LocalDate resetDate;

    public AiBudgetService(
            Clock clock,
            @Value("${company.nvidia-daily-call-limit}") int dailyLimit) {

        this.clock = clock;
        this.dailyLimit = dailyLimit;
        this.resetDate = LocalDate.now(clock);
    }

    public synchronized boolean isExhausted() {
        resetIfNewDay();
        return callsToday.get() >= dailyLimit;
    }

    public synchronized void recordCall() {
        resetIfNewDay();
        callsToday.incrementAndGet();
    }

    private void resetIfNewDay() {

        var today = LocalDate.now(clock);

        if (!today.equals(resetDate)) {
            resetDate = today;
            callsToday.set(0);
        }
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `cd app && mvn test -Dtest=AiBudgetServiceTest`
Expected: PASS, 4/4.

- [ ] **Step 5: Agregar el bean `Clock` a `CoreConfig`**

En `app/src/main/java/com/aicompany/core/config/CoreConfig.java`, agregar el import:

```java
import java.time.Clock;
```

Y agregar este bean nuevo (junto a los otros `@Bean` de la clase):

```java
    @Bean
    Clock systemClock() {
        return Clock.systemDefaultZone();
    }
```

- [ ] **Step 6: Compilar todo el proyecto y confirmar que la suite sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 224/224 (220 + 4 nuevos).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/AiBudgetService.java \
        app/src/test/java/com/aicompany/core/service/AiBudgetServiceTest.java \
        app/src/main/java/com/aicompany/core/config/CoreConfig.java
git commit -m "Agregar AiBudgetService (tope diario de cortesia para NVIDIA)"
```

---

### Task 5: Cablear el fallback en `CeoService` + configuración + verificación en vivo

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Modify: `app/src/main/java/com/aicompany/core/config/LlmConfig.java`
- Modify: `app/src/main/resources/application.yml`
- Modify: `app/docker-compose.yml`
- Modify: `app/src/test/java/com/aicompany/core/service/CeoServiceToolFormatGuardTest.java`
- Modify: `app/src/test/java/com/aicompany/core/service/CeoServiceCompanyMemoryTopicTest.java`
- Modify: `app/src/test/java/com/aicompany/core/service/CeoServiceChatHistoryTest.java`
- Create: `app/src/test/java/com/aicompany/core/service/CeoServiceProviderFallbackTest.java`

**Interfaces:**
- Consumes: `LlmProvider`/`LlmResponse` (Task 1), `OllamaLlmProvider` (Task 2), `NvidiaNimLlmProvider` (Task 3), `AiBudgetService` (Task 4).
- Produces: `CeoService` con constructor nuevo (ver Step 3) y `chat`/`executeMission` enrutados a través del `LlmProvider` configurado con fallback — sin cambio de firma pública en `chat`/`executeMission`/`executeAgentTask`.

- [ ] **Step 1: Completar `LlmConfig` con el bean de NVIDIA y el selector `ceoProvider`**

En `app/src/main/java/com/aicompany/core/config/LlmConfig.java`, agregar el import:

```java
import com.aicompany.core.llm.NvidiaNimLlmProvider;
import tools.jackson.databind.json.JsonMapper;
```

Y agregar estos dos beans nuevos, después de `ollamaLlmProvider`:

```java
    @Bean
    NvidiaNimLlmProvider nvidiaNimLlmProvider(
            @Value("${nvidia.base-url}") String baseUrl,
            @Value("${nvidia.api-key}") String apiKey,
            @Value("${nvidia.ceo-model}") String model,
            @Value("${nvidia.max-tokens}") int maxTokens,
            JsonMapper jsonMapper) {

        return new NvidiaNimLlmProvider(baseUrl, apiKey, model, maxTokens, jsonMapper);
    }

    @Bean
    com.aicompany.core.llm.LlmProvider ceoProvider(
            @Value("${llm.ceo-provider}") String ceoProviderName,
            OllamaLlmProvider ollamaLlmProvider,
            NvidiaNimLlmProvider nvidiaNimLlmProvider) {

        return "nvidia".equalsIgnoreCase(ceoProviderName)
                ? nvidiaNimLlmProvider
                : ollamaLlmProvider;
    }
```

(el bean `ceoProvider` toma el nombre de su método — `CeoService` lo pide por ese nombre exacto con `@Qualifier("ceoProvider")` en el Step 3).

- [ ] **Step 2: Agregar la configuración nueva a `application.yml`**

En `app/src/main/resources/application.yml`, agregar, después del bloque `ollama:` ya existente:

```yaml
llm:
  ceo-provider: ${LLM_CEO_PROVIDER:ollama}

nvidia:
  api-key: ${NVIDIA_API_KEY:}
  base-url: ${NVIDIA_BASE_URL:https://integrate.api.nvidia.com/v1}
  ceo-model: ${NVIDIA_CEO_MODEL:openai/gpt-oss-20b}
  max-tokens: ${NVIDIA_MAX_TOKENS:8192}
```

Y agregar esta línea dentro del bloque `company:` ya existente (junto a `seed-capital-usd`/`challenge-days`):

```yaml
  nvidia-daily-call-limit: ${NVIDIA_DAILY_CALL_LIMIT:20}
```

- [ ] **Step 3: Cablear `CeoService` para usar `callCeo(...)` en `chat`/`executeMission`**

En `app/src/main/java/com/aicompany/core/service/CeoService.java`, agregar los imports:

```java
import com.aicompany.core.llm.LlmProvider;
import com.aicompany.core.llm.NvidiaNimLlmProvider;
import com.aicompany.core.llm.OllamaLlmProvider;
import org.springframework.beans.factory.annotation.Qualifier;
```

Reemplazar el bloque de campos y constructor:

```java
    private final RestClient ollama;
    private final String ceoModel;
    private final String agentModel;
    private final JsonMapper jsonMapper;
    private final EvidenceAcquisitionService evidenceAcquisitionService;
    private final CompanyEventPublisher events;
    private final MeterRegistry meterRegistry;

    public CeoService(
            RestClient ollama,
            @Value("${ollama.ceo-model}") String ceoModel,
            @Value("${ollama.agent-model}") String agentModel,
            JsonMapper jsonMapper,
            EvidenceAcquisitionService evidenceAcquisitionService,
            CompanyEventPublisher events,
            MeterRegistry meterRegistry) {

        this.ollama = ollama;
        this.ceoModel = ceoModel;
        this.agentModel = agentModel;
        this.jsonMapper = jsonMapper;
        this.evidenceAcquisitionService = evidenceAcquisitionService;
        this.events = events;
        this.meterRegistry = meterRegistry;
    }
```

por:

```java
    private final RestClient ollama;
    private final String agentModel;
    private final JsonMapper jsonMapper;
    private final EvidenceAcquisitionService evidenceAcquisitionService;
    private final CompanyEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final LlmProvider ceoProvider;
    private final OllamaLlmProvider ollamaFallbackProvider;
    private final AiBudgetService aiBudgetService;

    public CeoService(
            RestClient ollama,
            @Value("${ollama.agent-model}") String agentModel,
            JsonMapper jsonMapper,
            EvidenceAcquisitionService evidenceAcquisitionService,
            CompanyEventPublisher events,
            MeterRegistry meterRegistry,
            @Qualifier("ceoProvider") LlmProvider ceoProvider,
            OllamaLlmProvider ollamaFallbackProvider,
            AiBudgetService aiBudgetService) {

        this.ollama = ollama;
        this.agentModel = agentModel;
        this.jsonMapper = jsonMapper;
        this.evidenceAcquisitionService = evidenceAcquisitionService;
        this.events = events;
        this.meterRegistry = meterRegistry;
        this.ceoProvider = ceoProvider;
        this.ollamaFallbackProvider = ollamaFallbackProvider;
        this.aiBudgetService = aiBudgetService;
    }
```

(`ceoModel` desaparece del todo: ya no lo usa nada en `CeoService` — el modelo del CEO ahora vive dentro de cada `LlmProvider` bean, configurado en `LlmConfig`).

Agregar el método `callCeo` nuevo (por ejemplo, justo antes de `private String systemPrompt()`):

```java
    /**
     * Único punto por el que pasan las dos llamadas del CEO ({@code chat}
     * y {@code executeMission}) al proveedor configurado — aplica el
     * presupuesto diario y el fallback a Ollama descritos en
     * `docs/superpowers/specs/2026-09-18-nvidia-ceo-provider-design.md`.
     * {@code executeAgentTask} no pasa por acá: sigue llamando a
     * {@link #callModel} (Ollama directo) sin cambios.
     *
     * <p>Un {@code content} nulo/vacío SIN {@code tool_calls} cuenta como
     * fallo (el truncamiento real observado en el spike con
     * `gpt-oss-20b`); un {@code content} vacío CON {@code tool_calls} es
     * un turno de herramienta normal, no un fallo.
     */
    private LlmResponse callCeo(
            String operation,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {

        var usingNvidia = ceoProvider instanceof NvidiaNimLlmProvider;

        if (usingNvidia && aiBudgetService.isExhausted()) {

            log.info("CEO_PROVIDER_FALLBACK operation={} reason=BUDGET_EXHAUSTED", operation);

            events.publish(
                    "EMPRESA_CEO_PROVIDER_FALLBACK",
                    null, null, "ceo",
                    Map.of("operation", operation, "reason", "BUDGET_EXHAUSTED")
            );

            return ollamaFallbackProvider.chat(operation, messages, tools);
        }

        try {

            var response = ceoProvider.chat(operation, messages, tools);

            var noContentNoToolCalls =
                    (response.content() == null || response.content().isBlank())
                            && (response.toolCalls() == null || response.toolCalls().isEmpty());

            if (noContentNoToolCalls) {
                throw new IllegalStateException(
                        "Respuesta vacía del proveedor CEO (sin contenido ni tool_calls)."
                );
            }

            if (usingNvidia) {
                aiBudgetService.recordCall();
            }

            return response;

        } catch (Exception ex) {

            if (!usingNvidia) {
                throw ex;
            }

            log.warn("CEO_PROVIDER_FALLBACK operation={} reason=RATE_LIMIT_OR_ERROR", operation, ex);

            events.publish(
                    "EMPRESA_CEO_PROVIDER_FALLBACK",
                    null, null, "ceo",
                    Map.of("operation", operation, "reason", "RATE_LIMIT_OR_ERROR")
            );

            return ollamaFallbackProvider.chat(operation, messages, tools);
        }
    }
```

En el método `chat(...)`, reemplazar:

```java
        var turn = callModel(
                "CEO_CHAT", "ceo", ceoModel, messages, null, COMPANY_MEMORY_TOOLS
        );
```

por:

```java
        var turn = callCeo("CEO_CHAT", messages, COMPANY_MEMORY_TOOLS);
```

Y, más abajo en el mismo método, reemplazar:

```java
        var finalTurn = callModel(
                "CEO_CHAT", "ceo", ceoModel, messages, null, null
        );
```

por:

```java
        var finalTurn = callCeo("CEO_CHAT", messages, null);
```

En el método `executeMission(...)`, reemplazar:

```java
        return callModel(
                "MISSION_CONSOLIDATION", "ceo", ceoModel, messages, null, null
        ).content();
```

por:

```java
        return callCeo("MISSION_CONSOLIDATION", messages, null).content();
```

- [ ] **Step 4: Actualizar los 3 call-sites de test que construyen `CeoService` directamente**

En `app/src/test/java/com/aicompany/core/service/CeoServiceToolFormatGuardTest.java`, agregar los imports:

```java
import com.aicompany.core.llm.LlmProvider;
import com.aicompany.core.llm.OllamaLlmProvider;
```

y reemplazar la construcción de `ceoService`:

```java
    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            "qwen2.5-coder:14b",
            "qwen3:8b",
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );
```

por:

```java
    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            "qwen3:8b",
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry(),
            mock(LlmProvider.class),
            mock(OllamaLlmProvider.class),
            mock(AiBudgetService.class)
    );
```

Repetir exactamente el mismo cambio (mismos imports nuevos, mismo reemplazo de la construcción de `ceoService`) en:
- `app/src/test/java/com/aicompany/core/service/CeoServiceCompanyMemoryTopicTest.java`
- `app/src/test/java/com/aicompany/core/service/CeoServiceChatHistoryTest.java`

- [ ] **Step 5: Ejecutar los 3 tests actualizados y confirmar que siguen pasando**

Run: `cd app && mvn test -Dtest=CeoServiceToolFormatGuardTest,CeoServiceCompanyMemoryTopicTest,CeoServiceChatHistoryTest`
Expected: PASS — mismos casos que antes, ninguno nuevo (este step solo repara la compilación tras el cambio de constructor).

- [ ] **Step 6: Escribir `CeoServiceProviderFallbackTest` (falla primero: `callCeo` no publica el evento todavía en esta rama de ejecución... en realidad ya existe tras el Step 3, así que este test debe pasar directo — igual se ejecuta para confirmarlo, no para TDD estricto de un método ya escrito)**

Crear `app/src/test/java/com/aicompany/core/service/CeoServiceProviderFallbackTest.java`:

```java
package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import com.aicompany.core.llm.LlmProvider;
import com.aicompany.core.llm.LlmResponse;
import com.aicompany.core.llm.NvidiaNimLlmProvider;
import com.aicompany.core.llm.OllamaLlmProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fallback de CEO en NVIDIA -> Ollama (ver
 * `docs/superpowers/specs/2026-09-18-nvidia-ceo-provider-design.md`).
 * Usa `NvidiaNimLlmProvider` mockeado como `ceoProvider` a propósito:
 * distinguir "estamos usando NVIDIA" de "estamos usando Ollama" es un
 * chequeo `instanceof` dentro de `CeoService.callCeo`, así que el mock
 * necesita ser del tipo concreto NVIDIA para ejercitar esa rama.
 */
class CeoServiceProviderFallbackTest {

    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final OllamaLlmProvider ollamaFallback = mock(OllamaLlmProvider.class);
    private final AiBudgetService budget = mock(AiBudgetService.class);
    private final NvidiaNimLlmProvider nvidiaProvider = mock(NvidiaNimLlmProvider.class);

    private CeoService ceoService(LlmProvider ceoProvider) {
        return new CeoService(
                mock(RestClient.class),
                "qwen3:8b",
                JsonMapper.builder().build(),
                mock(EvidenceAcquisitionService.class),
                events,
                new SimpleMeterRegistry(),
                ceoProvider,
                ollamaFallback,
                budget
        );
    }

    @Test
    void usesNvidiaResponseDirectlyWhenHealthyAndBudgetAvailable() {

        when(budget.isExhausted()).thenReturn(false);
        when(nvidiaProvider.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("respuesta real de NVIDIA", List.of()));

        var result = ceoService(nvidiaProvider).executeMission("instruccion", "resultados");

        assertEquals("respuesta real de NVIDIA", result);
        verify(budget).recordCall();
        verifyNoInteractions(ollamaFallback);
        verifyNoInteractions(events);
    }

    @Test
    void fallsBackToOllamaWhenNvidiaThrows() {

        when(budget.isExhausted()).thenReturn(false);
        when(nvidiaProvider.chat(anyString(), any(), any()))
                .thenThrow(new RuntimeException("timeout"));
        when(ollamaFallback.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("respuesta real de Ollama", List.of()));

        var result = ceoService(nvidiaProvider).executeMission("instruccion", "resultados");

        assertEquals("respuesta real de Ollama", result);
        verify(budget, never()).recordCall();
        verify(events).publish(
                eq("EMPRESA_CEO_PROVIDER_FALLBACK"), any(), any(), eq("ceo"),
                eq(Map.of("operation", "MISSION_CONSOLIDATION", "reason", "RATE_LIMIT_OR_ERROR"))
        );
    }

    @Test
    void fallsBackToOllamaWhenNvidiaReturnsEmptyContentAndNoToolCalls() {

        when(budget.isExhausted()).thenReturn(false);
        when(nvidiaProvider.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse(null, List.of()));
        when(ollamaFallback.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("respuesta real de Ollama", List.of()));

        var result = ceoService(nvidiaProvider).executeMission("instruccion", "resultados");

        assertEquals("respuesta real de Ollama", result);
        verify(events).publish(
                eq("EMPRESA_CEO_PROVIDER_FALLBACK"), any(), any(), eq("ceo"),
                eq(Map.of("operation", "MISSION_CONSOLIDATION", "reason", "RATE_LIMIT_OR_ERROR"))
        );
    }

    @Test
    void fallsBackToOllamaWhenBudgetAlreadyExhausted() {

        when(budget.isExhausted()).thenReturn(true);
        when(ollamaFallback.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("respuesta real de Ollama", List.of()));

        var result = ceoService(nvidiaProvider).executeMission("instruccion", "resultados");

        assertEquals("respuesta real de Ollama", result);
        verifyNoInteractions(nvidiaProvider);
        verify(events).publish(
                eq("EMPRESA_CEO_PROVIDER_FALLBACK"), any(), any(), eq("ceo"),
                eq(Map.of("operation", "MISSION_CONSOLIDATION", "reason", "BUDGET_EXHAUSTED"))
        );
    }

    @Test
    void neverConsultsBudgetOrFallsBackWhenConfiguredProviderIsOllama() {

        var ollamaAsCeoProvider = mock(OllamaLlmProvider.class);
        when(ollamaAsCeoProvider.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("respuesta real de Ollama", List.of()));

        var result = ceoService(ollamaAsCeoProvider).executeMission("instruccion", "resultados");

        assertEquals("respuesta real de Ollama", result);
        verifyNoInteractions(budget);
        verifyNoInteractions(ollamaFallback);
    }
}
```

- [ ] **Step 7: Ejecutar el test nuevo y confirmar que pasa**

Run: `cd app && mvn test -Dtest=CeoServiceProviderFallbackTest`
Expected: PASS, 5/5.

- [ ] **Step 8: Agregar la configuración nueva a `docker-compose.yml`**

En `app/docker-compose.yml` (raíz del repo, `docker-compose.yml`), agregar estas líneas al bloque `environment:` del servicio `company-core`, después de `OLLAMA_AGENT_MODEL`:

```yaml
      LLM_CEO_PROVIDER: ${LLM_CEO_PROVIDER:-ollama}
      NVIDIA_API_KEY: ${NVIDIA_API_KEY:-}
      NVIDIA_DAILY_CALL_LIMIT: ${NVIDIA_DAILY_CALL_LIMIT:-20}
```

Usar la sintaxis `${VAR:-default}` de Docker Compose (no `${VAR}` a secas, como sí hacen `NEO4J_PASSWORD`/`EVIDENCE_WEB_SEARCH_API_KEY` en este mismo archivo) es deliberado acá: si `.env` no define `LLM_CEO_PROVIDER`, Compose debe resolverlo a `ollama` **antes** de pasarlo al contenedor — si Compose pasara una cadena vacía, Spring vería la variable de entorno como presente (aunque vacía) y no aplicaría su propio default `${LLM_CEO_PROVIDER:ollama}` de `application.yml`, dejando el CEO sin proveedor configurado.

- [ ] **Step 9: Compilar todo el proyecto y confirmar que la suite completa sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 229/229 (224 + 5 nuevos de `CeoServiceProviderFallbackTest`).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java \
        app/src/main/java/com/aicompany/core/config/LlmConfig.java \
        app/src/main/resources/application.yml \
        app/docker-compose.yml \
        app/src/test/java/com/aicompany/core/service/CeoServiceToolFormatGuardTest.java \
        app/src/test/java/com/aicompany/core/service/CeoServiceCompanyMemoryTopicTest.java \
        app/src/test/java/com/aicompany/core/service/CeoServiceChatHistoryTest.java \
        app/src/test/java/com/aicompany/core/service/CeoServiceProviderFallbackTest.java
git commit -m "Cablear fallback NVIDIA->Ollama en CeoService (llm.ceo-provider, presupuesto diario)"
```

---

## Verificación en vivo (después de completar las 5 tareas)

No cubierto por `mvn test` (mismo criterio del proyecto: sin integración real contra Ollama/NVIDIA en la suite automatizada) — hacer esto contra el stack Docker real antes de dar la ronda por cerrada:

1. **Camino default (sin tocar nada)**: reconstruir/redeploy sin `NVIDIA_API_KEY` ni `LLM_CEO_PROVIDER` en `.env` — confirmar que el chat y una misión real siguen funcionando exactamente igual que antes (proveedor Ollama, cero eventos `EMPRESA_CEO_PROVIDER_FALLBACK`, cero llamadas de red a NVIDIA).
2. **Camino NVIDIA sano**: setear `NVIDIA_API_KEY` real (tier gratuito) y `LLM_CEO_PROVIDER=nvidia` en `.env`, redeploy. Confirmar en el log: `CEO_CHAT`/`MISSION_CONSOLIDATION` respondiendo con contenido real, sin `CEO_PROVIDER_FALLBACK`. Probar específicamente una pregunta que dispare `query_company_memory` (p. ej. "dame un status") y confirmar que el tool-calling de NVIDIA funciona de punta a punta (la normalización de `arguments` de la Task 3 es la pieza que lo permite).
3. **Camino de fallback real**: con `LLM_CEO_PROVIDER=nvidia` pero una `NVIDIA_API_KEY` inválida (o vacía), confirmar que el chat/misión igual responden (fallback a Ollama) y que aparece `EMPRESA_CEO_PROVIDER_FALLBACK` con `reason=RATE_LIMIT_OR_ERROR` en `EMPRESA_EVENTS`.
4. **Presupuesto agotado**: con `NVIDIA_API_KEY` real, hacer más de `NVIDIA_DAILY_CALL_LIMIT` llamadas al CEO en el mismo día (o bajar el límite a 1 vía `NVIDIA_DAILY_CALL_LIMIT=1` para la prueba) y confirmar `reason=BUDGET_EXHAUSTED` en el evento correspondiente, y que el chat sigue respondiendo (vía Ollama) en vez de fallar.
5. Revertir `.env`/`docker-compose.yml` a los valores reales de producción y recrear el contenedor, confirmando `git diff` limpio (mismo procedimiento ya usado en la ronda de "Alertas por correo" para no dejar credenciales de prueba committeadas).

Documentar los resultados reales (no asumidos) en `CLAUDE.md`, siguiendo la convención ya establecida en este proyecto de escribir la sección de documentación después de la verificación en vivo, no antes.

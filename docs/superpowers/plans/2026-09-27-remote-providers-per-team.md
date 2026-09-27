# Proveedores remotos por grupo + chat con varios agentes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cada grupo de agentes usa un modelo fuerte de NVIDIA con su propia key (Engineering sin cambios), el proveedor remoto soporta herramientas, y el chat responde por defecto Alex pero admite `@menciones` a cualquier agente.

**Architecture:** `Agent.model = <proveedor>:<modelo>`; `CoreConfig` arma un `OpenAiCompatibleClient` por proveedor (`nvidia`, `nvidia-discovery`, `nvidia-creative`, `nvidia-ceo`) y `CeoService.callModel` elige por prefijo. El cliente remoto traduce herramientas entre el formato de Ollama (que usa `CeoService`) y el de OpenAI. El chat: `MentionResolver` (Java) detecta `@menciones`; `ChatIntentRouter` resuelve primero la gobernanza, luego las menciones (cada agente responde con `CeoService.agentChat`, su identidad, prompt, modelo y `query_company_memory` de solo lectura), luego lo de siempre.

**Tech Stack:** Java 21 / Spring Boot 4.1.1, API de NVIDIA (compatible con OpenAI), React/TS.

**Spec:** `docs/superpowers/specs/2026-09-27-remote-providers-per-team-design.md`

## Global Constraints

- Rama `proveedores-por-grupo`. Commits con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Engineering no cambia: `nvidia:moonshotai/kimi-k3` con `NVIDIA_API_KEY`.
- Proveedores: `nvidia` → `NVIDIA_API_KEY`; `nvidia-discovery` → `NVIDIA_API_KEY_DISCOVERY`; `nvidia-creative` → `NVIDIA_API_KEY_CREATIVE`; `nvidia-ceo` → `NVIDIA_API_KEY_CEO`. Base URL `https://integrate.api.nvidia.com/v1`. Las keys solo en `.env`.
- Prefijo remoto desconocido o proveedor sin key → `IllegalStateException` con mensaje claro; nunca cae a Ollama en silencio.
- Nunca `format` + `tools` en la misma llamada (guard existente, también en remoto). "Thinking" remoto siempre apagado.
- Tope de salida remoto: 16.384 en llamadas de equipo; 4.096 en el resto.
- Chat: sin mención responde Alex; gobernanza antes que menciones; un agente mencionado solo lee (`query_company_memory`), nunca lanza, aprueba, rechaza ni contacta.
- Suite `company-core` en verde en cada tarea (baseline 471); `npm run lint && npm run build` en la tarea del frontend.

## Review Focus

- Una conversación larga con varios hablantes: el historial de un agente no debe presentarle como propios los mensajes de otro agente — test en Task 4.
- `@Kira dame un status`: la mención gana sobre la consulta determinista y responde Kira, no el atajo de Java — test en Task 4.
- `aprueba MISSION-X @Kira`: la gobernanza gana; la decisión se registra y Kira no ejecuta nada — test en Task 4.
- Una respuesta remota con `tool_calls` cuyos `arguments` no son JSON válido: la llamada se descarta como si no hubiera pedido herramienta, sin excepción — test en Task 2.
- Una key de grupo inválida (401): el agente falla con el motivo; en el chat se responde el error de ese agente y los demás mencionados igual responden — test en Task 4.

---

### Task 1: Proveedores remotos con nombre

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/config/CoreConfig.java` (mapa de clientes)
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java` (ruteo por prefijo)
- Modify: `app/src/main/resources/application.yml`, `docker-compose.yml`
- Test: `app/src/test/java/com/aicompany/core/service/CeoServiceRemoteModelTest.java`

**Interfaces:**
- Produces: `CeoService(RestClient, JsonMapper, EvidenceAcquisitionService, CompanyEventPublisher, MeterRegistry, Map<String, OpenAiCompatibleClient> remotes)`; el constructor de 5 argumentos delega con `Map.of()`. `static Optional<RemoteModel> CeoService.remoteModel(String agentModel)` con `record RemoteModel(String provider, String model)` — reconoce `^(nvidia(?:-[a-z]+)?):(.+)$`.

- [ ] **Step 1: Tests (fallan)** — en `CeoServiceRemoteModelTest` reemplazar el constructor por el mapa `Map.of("nvidia", remote, "nvidia-ceo", ceoRemote)` y agregar:

```java
    @Test
    void eachProviderPrefixUsesItsOwnClient() {
        when(ceoRemote.chat(eq("nvidia/nemotron-3-ultra-550b-a55b"), anyList(), eq(true), anyInt()))
                .thenReturn("{\\"summary\\":\\"s\\",\\"tasks\\":[]}");
        assertThrows(IllegalStateException.class, () ->
                ceoService.planTeamWork("ceo", "p", "", "nvidia-ceo:nvidia/nemotron-3-ultra-550b-a55b"));
        verify(ceoRemote).chat(eq("nvidia/nemotron-3-ultra-550b-a55b"), anyList(), eq(true), anyInt());
        verifyNoInteractions(remote);
    }

    @Test
    void anUnknownRemoteProviderFailsClearlyInsteadOfFallingBackToOllama() {
        var ex = assertThrows(IllegalStateException.class, () ->
                ceoService.generateDevelopmentArtifact("backend", "p", "", "nvidia-otro:x/y"));
        assertTrue(ex.getMessage().contains("nvidia-otro"), ex.getMessage());
        verifyNoInteractions(ollama);
    }

    @Test
    void remoteModelParsing() {
        assertEquals(Optional.of(new CeoService.RemoteModel("nvidia-discovery", "nvidia/nemotron-3-super-120b-a12b")),
                CeoService.remoteModel("nvidia-discovery:nvidia/nemotron-3-super-120b-a12b"));
        assertEquals(Optional.empty(), CeoService.remoteModel("qwen3:8b"));
    }
```
(`planTeamWork` con `{"summary":"s","tasks":[]}` falla la validación del JSON del plan → `IllegalStateException` esperado; lo que se verifica es el cliente usado.)

- [ ] **Step 2: Correr** → FAIL.

- [ ] **Step 3: Implementar**
  - `CeoService`: campo `Map<String, OpenAiCompatibleClient> remotes`; `REMOTE_MODEL_PREFIX` reemplazado por `REMOTE_MODEL = Pattern.compile("^(nvidia(?:-[a-z]+)?):(.+)$")`; `remoteModel(...)`; en `callModel`, si `remoteModel(model)` está presente: `var client = remotes.get(provider)`; si es `null` → `IllegalStateException("No hay proveedor remoto configurado para \"" + provider + "\" (modelo " + model + ").")`; si no, la rama remota actual con ese cliente. `rejectToolsForRemoteModels` se elimina (Task 2 agrega herramientas).
  - `CoreConfig`: bean `Map<String, OpenAiCompatibleClient> remoteModelClients` que lee `remote-models.providers.{nvidia,nvidia-discovery,nvidia-creative,nvidia-ceo}.api-key` (via `Environment`), mismo `base-url` y `read-timeout`; reemplaza el bean único.
  - `application.yml`:
```yaml
remote-models:
  base-url: ${REMOTE_MODELS_BASE_URL:https://integrate.api.nvidia.com/v1}
  read-timeout: ${REMOTE_MODELS_READ_TIMEOUT:10m}
  providers:
    nvidia:
      api-key: ${NVIDIA_API_KEY:}
    nvidia-discovery:
      api-key: ${NVIDIA_API_KEY_DISCOVERY:}
    nvidia-creative:
      api-key: ${NVIDIA_API_KEY_CREATIVE:}
    nvidia-ceo:
      api-key: ${NVIDIA_API_KEY_CEO:}
```
  - `docker-compose.yml` (`company-core.environment`): `NVIDIA_API_KEY_DISCOVERY`, `NVIDIA_API_KEY_CREATIVE`, `NVIDIA_API_KEY_CEO` desde `.env`.

- [ ] **Step 4: Correr** → suite completa en verde. **Step 5: Commit** — `git commit -m "Proveedores remotos con nombre: una key por grupo de agentes"`

---

### Task 2: Herramientas en el proveedor remoto

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/OpenAiCompatibleClient.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java` (rama remota devuelve `tool_calls`)
- Test: `OpenAiCompatibleClientTest.java`, `CeoServiceRemoteModelTest.java`

**Interfaces:**
- Produces: `OpenAiCompatibleClient.complete(String model, List<Map<String,Object>> messages, List<Map<String,Object>> tools, boolean json, int maxTokens) → RemoteReply(String content, List<Map<String,Object>> toolCalls)` (tool calls ya en formato Ollama: `{"function": {"name", "arguments": Map}}`); `chat(...)` queda como atajo sin tools que devuelve `content`.

- [ ] **Step 1: Tests (fallan)**

```java
    // Verificado en vivo (2026-09-27): los 4 candidatos piden herramientas en formato OpenAI (arguments como texto).
    @Test
    void toolsAreSentAndToolCallsComeBackInOllamaFormat() {
        var builder = RestClient.builder().baseUrl("https://api.test/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.test/v1/chat/completions"))
                .andExpect(jsonPath("$.tools[0].function.name").value("search_web_evidence"))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"","tool_calls":[
                          {"id":"call-1","type":"function","function":{"name":"search_web_evidence","arguments":"{\\"query\\":\\"x\\"}"}},
                          {"id":"call-2","type":"function","function":{"name":"search_web_evidence","arguments":"{\\"query\\":\\"y\\"}"}}]}}]}""",
                        MediaType.APPLICATION_JSON));

        var reply = new OpenAiCompatibleClient(builder.build(), "k", Duration.ZERO).complete("m", MESSAGES,
                List.of(Map.of("type", "function", "function", Map.of("name", "search_web_evidence"))), false, 4096);

        assertEquals(2, reply.toolCalls().size());
        assertEquals(Map.of("name", "search_web_evidence", "arguments", Map.of("query", "x")),
                reply.toolCalls().get(0).get("function"));
    }

    // Review Focus: arguments que no son JSON → esa llamada se descarta, sin excepción.
    @Test
    void toolCallsWithInvalidArgumentsAreDropped() { /* misma respuesta con "arguments":"no-json" → toolCalls vacío */ }

    // La historia con tool_calls de Ollama se traduce: id + arguments como texto + tool_call_id en el mensaje tool.
    @Test
    void anOllamaToolRoundTripIsTranslated() {
        // messages: system, user, assistant{content:"", tool_calls:[{function:{name, arguments:{query:"x"}}}]}, tool{content:"r"}
        // expect jsonPath("$.messages[2].tool_calls[0].id").value("call_0"),
        //        jsonPath("$.messages[2].tool_calls[0].function.arguments").value("{\"query\":\"x\"}"),
        //        jsonPath("$.messages[3].tool_call_id").value("call_0")
    }
```
(escribir los dos últimos completos con `MockRestServiceServer`, igual que el primero).

En `CeoServiceRemoteModelTest`: `aRemoteChatCanUseTheCompanyMemoryTool` — `complete(...)` devuelve primero un `tool_call` `query_company_memory {topic: AGENT_STATUS}` y después `content` "respuesta"; `ceoService.chat(..., "nvidia-ceo:m")` devuelve "respuesta" y el callback de memoria se llamó con `AGENT_STATUS`.

- [ ] **Step 2: Correr** → FAIL.

- [ ] **Step 3: Implementar**
  - `complete(...)`: arma el body como `chat` y, si hay `tools`, agrega `"tools"` (misma estructura). Traduce `messages`: por cada `assistant` con `tool_calls`, asigna `id = "call_" + n` (contador), `type = "function"` y `arguments` serializado a texto JSON; el `tool` siguiente recibe `tool_call_id` = el último `id`. Respuesta: `tool_calls` → lista en formato Ollama con `arguments` parseado (`JsonMapper`); si el parseo falla, se descarta esa llamada.
  - `CeoService.callModel` rama remota: `var reply = client.complete(remote.model(), messages, tools, format != null, maxTokens)` y `return new ModelMessage(reply.content(), reply.toolCalls())`; tope 4.096 fuera de las operaciones de equipo.

- [ ] **Step 4: Correr** → suite en verde. **Step 5: Commit** — `git commit -m "Herramientas en el proveedor remoto: traducción Ollama ↔ OpenAI"`

---

### Task 3: `CeoService.agentChat` — un agente responde en el chat

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Test: `app/src/test/java/com/aicompany/core/service/CeoServiceAgentChatTest.java`

**Interfaces:**
- Produces: `record ChatSpeaker(String agentId, String name, String role, String personality)`; `CeoService.agentChat(ChatSpeaker speaker, String teamRoster, List<ConversationTurn> history, String message, Function<String,String> companyMemoryQuery, String agentPrompt, String model) → String`. `chat(...)` de Alex queda igual por fuera y comparte la lógica (turno con `query_company_memory`, turno final).

- [ ] **Step 1: Tests (fallan)** — con Ollama mockeado (`MockRestServiceServer` como en `CeoServiceContextWindowTest`): el system prompt de `agentChat` para Kira contiene "Tu nombre es Kira", su rol, su personalidad, "no puedes lanzar misiones, aprobar, rechazar ni contactar" y el roster; y **no** contiene "Eres el CEO de Forjai". El pedido lleva `tools` = `query_company_memory`.

- [ ] **Step 2: Correr** → FAIL.

- [ ] **Step 3: Implementar**: extraer de `chat` el flujo de dos turnos a `private String conversation(String operation, String actor, String system, List<ConversationTurn> history, String message, Function<String,String> memory, String model)`; `chat` arma su system actual y llama a `conversation("CEO_CHAT", "ceo", ...)`; `agentChat` arma:
```java
        var system = """
                Eres %s, %s de Forjai, una empresa real operada por agentes de IA. Tu nombre es %s.
                Personalidad: %s
                Estás en el chat de la empresa respondiendo al fundador (y a veces a otros agentes).
                No puedes lanzar misiones, aprobar, rechazar ni contactar a nadie: si te lo piden, dilo y remite a Alex
                (el CEO) o a los comandos de misión. Las acciones reservadas son solo del fundador.
                No inventes clientes, ventas, ingresos, búsquedas ni evidencia. Para datos reales de la empresa usa
                query_company_memory; si no hay dato, responde exactamente: "No tengo ese dato registrado."
                Equipo real:
                %s
                %s""".formatted(speaker.name(), speaker.role(), speaker.name(), speaker.personality(), teamRoster,
                agentPromptBlock(agentPrompt));
```
y `conversation("AGENT_CHAT", speaker.agentId(), system, ...)`.

- [ ] **Step 4: Correr** → verde. **Step 5: Commit** — `git commit -m "CeoService.agentChat: cualquier agente responde en el chat con su identidad y solo lectura"`

---

### Task 4: Menciones en el chat

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/MentionResolver.java`
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`, `app/src/main/java/com/aicompany/core/model/ChatResponse.java`, `app/src/main/java/com/aicompany/core/controller/CompanyController.java`
- Test: `MentionResolverTest.java`, `ChatIntentRouterTest.java`

**Interfaces:**
- Produces: `MentionResolver.resolve(String message, List<Map<String,Object>> agents) → Resolution(List<String> agentIds, List<String> unknown)` (orden de aparición, sin duplicados; compara nombre e id sin mayúsculas ni tildes); `record ChatReply(String agentId, String name, String text)`; `ChatResponse(String agent, String response, List<ChatReply> replies)` + constructor compatible `(agent, response)`; `ChatIntentRouter.routeReplies(String message) → List<ChatReply>` (y `route` devuelve el texto unido, para compatibilidad).

- [ ] **Step 1: Tests (fallan)**
  - `MentionResolverTest`: `@Kira` → `[growth-content]`; `@kíra @SOFIA` → `[growth-content, sales]`; `@growth-content` → `[growth-content]`; `@Pepe` → `unknown=[Pepe]`; sin `@` → vacío; un email `a@b.com` no es mención (la `@` tiene que ir al inicio o tras espacio).
  - `ChatIntentRouterTest`:
    1. `aMentionIsAnsweredByThatAgentAndRecordedWithItsId`: `@Kira ideas` → `ceoService.agentChat(speaker Kira, ...)`, nunca `chat`; `recordMessage("growth-content", ...)`.
    2. `severalMentionsAnswerInOrder`.
    3. Review Focus `aMentionWinsOverDeterministicQueries`: `@Kira dame un status` → responde `agentChat`, no el formateo de `COMPANY_STATUS`.
    4. Review Focus `governanceWinsOverMentions`: `aprueba MISSION-1 porque sí @Kira` → `missionService.recordDecision` y `agentChat` nunca.
    5. Review Focus `anAgentsHistoryLabelsOtherSpeakers`: historial `[user "hola", ceo "soy Alex", growth-content "soy Kira"]` → para Kira: `ceo` llega como `user` con `[Alex (CEO)]: soy Alex` y su propio turno como `ceo` (assistant).
    6. `anUnknownMentionListsTheRealAgents` (determinista, sin modelo).
    7. Review Focus `aFailingAgentDoesNotSilenceTheOthers`: `agentChat` de Kira lanza → su respuesta es "Kira no pudo responder: …" y Sofia igual responde.

- [ ] **Step 2: Correr** → FAIL.

- [ ] **Step 3: Implementar**
  - `MentionResolver`: `Pattern.compile("(?:^|\\s)@([\\p{L}\\p{N}_-]+)")`; normaliza (NFD sin marcas, minúsculas) nombre e id de cada agente.
  - `ChatIntentRouter.routeReplies(message)`: 1) gobernanza igual que hoy (arranque explícito, arranque libre, decisión) → una respuesta de Alex; 2) si hay menciones: desconocidas → mensaje determinista con "Agentes: Alex (ceo), Sofia (sales), …"; si no, por cada `agentId` → `historyFor(agentId)` + `ceoService.agentChat(...)` con `companyMemory.agentModel(agentId, defaultAgentModel)` y `promptMemory.activePrompt(agentId)`, capturando excepciones por agente; 3) sin menciones → el flujo actual (Alex). Graba el mensaje del usuario una vez y cada respuesta con su `agentId` (`ceo` para Alex).
  - `historyFor(speakerId)`: turnos de `recentMessages(HISTORY_LIMIT)`: `user` igual; los del propio hablante → rol `ceo` (assistant); los de otros agentes → rol `user` con `"[" + name + " (" + role + ")]: " + content`.
  - `CompanyController.chat`: `new ChatResponse(first.name(), joined, replies)`.

- [ ] **Step 4: Correr** → suite en verde. **Step 5: Commit** — `git commit -m "Chat con menciones: por defecto Alex, @agente responde con su identidad y modelo"`

---

### Task 5: Command Center — quién responde en el chat

**Files:** `app/frontend/src/api/types.ts`, `app/frontend/src/pages/ChatPage.tsx`

- [ ] **Step 1**: `types.ts`: `export interface ChatReply { agentId: string; name: string; text: string }` y `ChatResponse` gana `replies?: ChatReply[]`.
- [ ] **Step 2**: `ChatPage.tsx`: `Message` gana `name?: string`; `onSuccess` agrega un mensaje por cada `reply` (`{ from: 'ceo', name: r.name, text: r.text }`), o el `response` si no hay `replies`; el render muestra `m.name ?? 'CEO'`; el indicador "está pensando..." dice "Forjai está pensando..."; el `hint` menciona "Escribe @Nombre para sumar a un agente (p. ej. @Kira)".
- [ ] **Step 3**: `cd app/frontend && npm run lint && npm run build` → verde. **Step 4: Commit** — `git commit -m "Chat: muestra qué agente responde y explica las menciones"`

---

### Task 6: Prueba corta de modelos por grupo

Script en el scratchpad (no se commitea) que usa las keys de cada grupo contra la API y los contratos reales:
- **Discovery** (`NVIDIA_API_KEY_DISCOVERY`; nemotron-3-super y nemotron-3-ultra): con el `company-core` desplegado y el agente `sales` apuntando temporalmente a cada candidato (`PUT /agents/sales/model`), una misión de discovery `TEST` corta; medir: tareas `COMPLETED` sin reintentos, evidencia citada, `AGENT_TOOL_TURN` con búsqueda real, latencia.
- **Creative y Marketing** (`NVIDIA_API_KEY_CREATIVE`; kimi-k3 y glm-5.3): una misión `TEAM-MARKETING-GROWTH` `TEST` corta por candidato.
- **CEO** (`NVIDIA_API_KEY_CEO`; nemotron-3-ultra y kimi-k3): 3 mensajes de chat (uno que requiera `query_company_memory`) y la consolidación de la misión de discovery.
- [ ] Presentar una tabla por grupo al fundador; **esperar su elección** (es una decisión suya, no un ruling).

### Task 7: Asignación, verificación en vivo y documentación

- [ ] **Step 1**: redeploy seguro; `PUT /api/company/agents/{id}/model` para los agentes de cada grupo con el modelo elegido y su prefijo (`nvidia-discovery:`, `nvidia-creative:`, `nvidia-ceo:`). Engineering no se toca.
- [ ] **Step 2 (en vivo)**: una misión de discovery `TEST` completa; una misión `TEAM-MARKETING-GROWTH` o `TEAM-CREATIVE-PRODUCT-INTELLIGENCE` `TEST`; en el chat: una pregunta a Alex que use `query_company_memory`, `@Kira @Sofia …` y `aprueba MISSION-… @Kira` (gobernanza gana).
- [ ] **Step 3**: `CLAUDE.md` (LLM: proveedores por grupo; Chat: menciones), `docs/HISTORY.md` (prueba de modelos, elección del fundador, verificación en vivo). **Step 4: Commit** — `git commit -m "Documentar proveedores por grupo y chat con menciones"`

---

## Self-review

- **Cobertura del spec**: §1 → T1; §2 → T2; §3 → T6; §4 → T7; §5 → T3–T5; privacidad → aceptada (sin tarea); testing → T1–T5 y T7.
- **Tipos**: `RemoteModel` (T1) en T2; `RemoteReply` (T2) en `callModel`; `ChatSpeaker` (T3) en T4; `ChatReply`/`ChatResponse` (T4) en T5.
- **Decisiones del fundador** que el plan no toma solo: la elección de modelos (T6 espera).

# Salud de los modelos remotos con suplente local — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que Forjai detecte la caída de un modelo remoto, deje de esperarlo, pase a los agentes afectados a su suplente local (explícitamente), detecte la recuperación sola y avise al fundador.

**Architecture:** `OpenAiCompatibleClient` distingue fallos de disponibilidad (`RemoteUnavailableException`) y ofrece `ping` con timeout corto. `ModelHealthService` guarda el estado por modelo en memoria, avisa (correo, eventos) y prueba cada 30 s los caídos. `CeoService.callModel` consulta el estado y desvía al suplente del agente (`Agent.fallbackModel`). Chat, API y pantalla Agents lo muestran.

**Tech Stack:** Java 21, Spring Boot 4.1.1 (`@Scheduled`), Neo4j, Kafka, JUnit 5 + Mockito + MockRestServiceServer; React + TS.

**Spec:** `docs/superpowers/specs/2026-09-28-salud-de-modelos-design.md`

## Global Constraints

- Solo modelos remotos (`nvidia*:`) se vigilan; clave de estado = el string completo `proveedor:modelo`.
- Fallo de disponibilidad: timeout/conexión, 5xx o 429 tras los reintentos, sin `choices`, cuerpo ilegible. Un 4xx distinto de 429 NO cuenta.
- 2 fallos de disponibilidad seguidos → `DOWN`; un éxito reinicia el contador.
- Suplente por agente en `Agent.fallbackModel`; default `qwen3-coder:30b`; vacío = sin suplente (falla rápido con motivo).
- Probe cada 30 s solo de modelos `DOWN`, 10 tokens, timeout 20 s.
- Nunca en silencio: correo (`AlertMailService.send`), eventos `EMPRESA_MODEL_DOWN|UP`, chat, marca `modelUsed` en tareas.

## Review Focus

- Dos agentes llaman al mismo modelo caído a la vez → uno solo dispara el correo/evento de caída (transición atómica) (test Task 2).
- El suplente también falla (Ollama apagado) → el error explica ambos fallos, no se queda esperando (test Task 3).
- Un modelo `DOWN` al que ya nadie usa sigue probándose y vuelve a `UP` (sin depender de que un agente lo llame) (test Task 2).
- `fallbackModel` configurado con un modelo remoto (p. ej. otro `nvidia:`) → se acepta (no solo locales) y pasa por la misma validación que el modelo principal (test Task 4).
- Reinicio de company-core con un modelo caído → el estado arranca `UP` y se re-detecta con el primer fallo doble (documentado; sin test).

---

### Task 1: Fallos de disponibilidad y `ping` en el cliente remoto

**Files:** Create `service/RemoteUnavailableException.java`; Modify `service/OpenAiCompatibleClient.java`, `config/CoreConfig.java`; Test `OpenAiCompatibleClientTest.java`.

**Interfaces (Produces):** `class RemoteUnavailableException extends IllegalStateException`; `OpenAiCompatibleClient(RestClient client, RestClient probeClient, String apiKey, Duration retryDelay)` (el de 3 args usa `client` como `probeClient`); `boolean ping(String model)` (true = 200 con `choices`; nunca lanza).

- [ ] **Step 1: Tests que fallan** (MockRestServiceServer, como los existentes):
  - 5xx en los 3 intentos → `RemoteUnavailableException` (mensaje con "HTTP 503").
  - 3 respuestas sin `choices` → `RemoteUnavailableException`.
  - `ResourceAccessException` (simular con `withException(new java.net.SocketTimeoutException())`) → `RemoteUnavailableException`.
  - cuerpo `application/octet-stream` → `RemoteUnavailableException`.
  - 400 → `IllegalStateException` que **no** es `RemoteUnavailableException` (actualizar `aClientErrorIsNotRetried` para comprobarlo).
  - `ping`: 200 con `choices` → true; 504 → false; timeout → false; manda `max_tokens` 10.
- [ ] **Step 2:** `mvn -q test -Dtest=OpenAiCompatibleClientTest` → FAIL.
- [ ] **Step 3:** Implementar: en `complete`, el reintento por 429/5xx y por falta de `choices` termina lanzando `RemoteUnavailableException`; `catch (ResourceAccessException | RestClientException ex)` (que no sea `RestClientResponseException` 4xx) → `RemoteUnavailableException("Modelo remoto X no responde: " + msg, ex)` sin reintentar (el tiempo ya se gastó). `ping`: POST mínimo (`{"role":"user","content":"ok"}`, `max_tokens` 10, thinking apagado) por `probeClient`, `try/catch (Exception) → false`. `CoreConfig`: por proveedor, un segundo `RestClient` con `readTimeout` 20 s como `probeClient`.
- [ ] **Step 4:** → PASS; suite verde.
- [ ] **Step 5: Commit** `Cliente remoto: fallos de disponibilidad tipados y ping con timeout corto`

---

### Task 2: `ModelHealthService` (estado, transiciones, avisos, probe)

**Files:** Create `service/ModelHealthService.java`, `model/ModelHealth.java`; Modify `CompanyMemoryService.java` (`agentFallbackModel`, `setAgentFallbackModel`, `agents()` con `fallbackModel`, `agentIdsUsingModel(String)`), una clase `@Configuration` con `@EnableScheduling` (p. ej. `AsyncConfig`); Test `service/ModelHealthServiceTest.java`.

**Interfaces (Produces):**
- `record ModelHealth(String model, String status /* UP|DOWN */, Instant since, String lastError, List<String> affectedAgents)`.
- `ModelHealthService(Map<String, OpenAiCompatibleClient> remotes, CompanyMemoryService companyMemory, CompanyEventPublisher events, AlertMailService mail)`.
- `boolean isDown(String model)`; `void recordSuccess(String model)`; `void recordFailure(String model, String error)`; `String fallbackFor(String agentId)` (`companyMemory.agentFallbackModel(agentId)`); `List<ModelHealth> snapshot()` (los modelos remotos en uso por algún agente, `UP` o `DOWN`); `@Scheduled(fixedDelay = 30_000) void probeDownModels()`.
- `CompanyMemoryService.agentFallbackModel(String id)` → `coalesce(a.fallbackModel, 'qwen3-coder:30b')` (string vacío guardado = sin suplente, se devuelve ""); `setAgentFallbackModel(String id, String model)`; `List<String> agentIdsUsingModel(String model)`.

- [ ] **Step 1: Tests que fallan** (memoria, eventos, correo y clientes mockeados; reloj real):
  - 1 fallo → sigue UP; 2 seguidos → DOWN, **un** `EMPRESA_MODEL_DOWN` y **un** correo con los agentes afectados y su suplente.
  - fallo, éxito, fallo → sigue UP.
  - dos hilos registrando el segundo fallo a la vez (llamar `recordFailure` dos veces más estando ya DOWN) → un solo evento/correo.
  - `probeDownModels`: con el modelo DOWN y `ping` true → UP, `EMPRESA_MODEL_UP` y correo de recuperación; con `ping` false → sigue DOWN, sin correo; un modelo UP no se prueba.
  - `snapshot` incluye el modelo DOWN con `since` y `affectedAgents`.
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3:** Implementar con `ConcurrentHashMap<String, State>` y transiciones en `compute(...)` para que solo una llamada gane el cambio a DOWN/UP; el proveedor sale de `CeoService.remoteModel(model)`. Correo: `mail.send("⚠ Forjai: " + short + " no responde en NVIDIA", cuerpo con agentes y suplentes, true)` y al volver `"✅ Forjai: " + short + " volvió"`. `@EnableScheduling` en la config.
- [ ] **Step 4:** → PASS; suite verde.
- [ ] **Step 5: Commit** `ModelHealthService: detección de caída, avisos y recuperación automática`

---

### Task 3: `CeoService` desvía al suplente

**Files:** Modify `service/CeoService.java` (setter `@Autowired(required = false) setModelHealth(ModelHealthService)`); Test `service/CeoServiceModelHealthTest.java`.

- [ ] **Step 1: Tests que fallan** (remoto mockeado, Ollama con MockRestServiceServer, `ModelHealthService` mockeado):
  - Modelo DOWN → no se llama al remoto; la llamada va a Ollama con el suplente (`$.model == "qwen3-coder:30b"`).
  - Modelo UP y `complete` lanza `RemoteUnavailableException` → `recordFailure`; si tras eso `isDown` → la misma llamada se repite en Ollama con el suplente y devuelve su respuesta.
  - Modelo UP y éxito → `recordSuccess`, nada a Ollama.
  - DOWN y sin suplente (`fallbackFor` → "") → `IllegalStateException` con "no tiene suplente" al instante.
  - DOWN y el suplente también falla → el error menciona ambos (modelo caído y fallo del suplente).
  - Un 4xx (`IllegalStateException` normal) no llama a `recordFailure`.
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3:** Implementar en la rama remota de `callModel`: `if (health != null && health.isDown(model)) return viaFallback(...)`; `try { reply = remote.complete(...); health.recordSuccess(model) } catch (RemoteUnavailableException ex) { health.recordFailure(model, ex.getMessage()); if (health.isDown(model)) return viaFallback(..., ex); throw ex; }`. `viaFallback` busca `health.fallbackFor(actor)`; vacío → `IllegalStateException("El modelo " + model + " no responde en NVIDIA desde " + since + " y " + actor + " no tiene suplente.")`; si no, log `MODEL_FALLBACK actor model fallback` y `callModel(operation, actor, fallback, messages, format, tools, think)` envolviendo cualquier excepción del suplente con ambos motivos.
- [ ] **Step 4:** → PASS; suite verde.
- [ ] **Step 5: Commit** `CeoService: un modelo caído se desvía al suplente local del agente`

---

### Task 4: Marca en tareas, API y chat

**Files:** Modify `agent/AgentRuntime.java`, `agent/DevelopmentRuntime.java` (al completar: `modelUsed`), `MissionMemoryService.java` (`setTaskModelUsed`, `modelsUsed(missionId)`), `controller/CompanyController.java` (`GET /api/company/models/health`, `PUT /api/company/agents/{id}/fallback-model` validado con `CeoService.checkModel` salvo vacío), `ChatIntentRouter.java`; Tests en los tests existentes de cada uno.

- [ ] **Step 1: Tests que fallan:**
  - `AgentRuntime`: tarea completada mientras el modelo principal del agente está DOWN → `setTaskModelUsed(taskId, suplente)`; con UP → no se marca.
  - `CompanyController`: `fallback-model` con `anthropic:x/y` → rechazo (usa `checkModel`); vacío → se guarda "" (sin suplente); `models/health` delega en `snapshot()`.
  - Chat: "estado de los modelos" → "⚠ kimi-k3 no responde desde HH:MM — Neo, Iris… trabajan con qwen3-coder:30b" y ✅ para los UP; "dame un status" agrega "Modelos caídos: …" solo si hay alguno; el estado de los agentes agrega "(con suplente qwen3-coder:30b)" al afectado; "¿cómo va MISSION-X?" lista las tareas hechas con suplente.
- [ ] **Step 2:** → FAIL.
- [ ] **Step 3:** Implementar (el router recibe `ModelHealthService`; el detalle de misión usa `missionMemory.modelsUsed`).
- [ ] **Step 4:** → PASS; suite verde.
- [ ] **Step 5: Commit** `Salud de modelos: marca en tareas, API y chat`

---

### Task 5: Command Center (Agents)

**Files:** Modify `frontend/src/pages/AgentsPage.tsx` (en `ModelEditor`: campo "Suplente local" con `api.updateAgentFallbackModel`, y aviso ⚠ si su modelo está DOWN), `api/types.ts` (`AgentInfo.fallbackModel`, `ModelHealth`), `api/client.ts` (`modelsHealth`, `updateAgentFallbackModel`).

- [ ] **Step 1:** implementar; `npm run lint && npm run build` sin errores nuevos; `mvn -q test` verde.
- [ ] **Step 2: Commit** `Command Center: suplente local por agente y aviso de modelo caído`

---

### Task 6: Documentación y verificación en vivo

- [ ] `CLAUDE.md` (sección LLM: salud de modelos y suplente), `docs/EVENTS.md` (`EMPRESA_MODEL_DOWN|UP`), `docs/HISTORY.md`.
- [ ] En vivo: si kimi-k3 sigue caído, una tarea de Engineering (p. ej. `@Neo` en el chat o una discovery TEST) → tras la detección, correo, chat "estado de los modelos", la tarea marcada con suplente y respuesta del suplente; cuando kimi-k3 vuelva, `EMPRESA_MODEL_UP` y correo. Si ya volvió, simular con un agente de prueba apuntando a un modelo remoto que no responda y restaurarlo.
- [ ] Commit `Documentar la salud de los modelos y su verificación en vivo`.

## Self-review

- Cobertura: §1 detección → T1/T2; §2 desvío → T3; §3 recuperación → T2; §4 avisos → T2 (correo, eventos), T4 (chat, tareas), T5 (pantalla); decisiones (suplente editable, explícito, solo remotos) → T2–T5.
- Tipos: `RemoteUnavailableException`/`ping` (T1) en T2/T3; `ModelHealthService` API (T2) en T3/T4/T5; `agentFallbackModel` (T2) en T3/T4.

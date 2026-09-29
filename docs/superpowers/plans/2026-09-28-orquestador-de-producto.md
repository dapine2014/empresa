# Orquestador del ciclo de producto — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un orquestador persistente que, sin productos listos ni en construcción, elige (o descubre) una idea, completa su ficha, la construye (Engineering o Creative) y la deja lista para vender, visible y pausable.

**Architecture:** `(:OrchestratorRun)` guarda el ciclo; `ProductOrchestrator.tick()` es una máquina de estados idempotente que mira Neo4j y avanza un paso, llamada por `@Scheduled` cada 15 min y al terminar cada misión. Las decisiones del modelo (elegir, ficha, entrega) son llamadas estructuradas de `CeoService` validadas en Java con fallback determinista.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Neo4j, Kafka, JUnit 5 + Mockito; React + TS.

**Spec:** `docs/superpowers/specs/2026-09-28-orquestador-de-producto-design.md`

## Global Constraints

- Estados `CHOOSING`, `DISCOVERING`, `PROPOSING`, `BUILDING`, `READY`, `FAILED`, `STOPPED`; activos = los 4 primeros.
- Policies nuevas: `ORCHESTRATOR_ENABLED` (1 = encendido, default 1) y `MAX_AUTONOMOUS_PRODUCTS` (default 1).
- Arranca solo sin productos `READY_TO_SELL` ni `IN_CONSTRUCTION`, con el orquestador encendido y ciclos activos < límite.
- Ideas del fundador (`createdBy: human`) primero y sin pasar por Alex; si no, Alex elige entre ideas con evidencia web; fallback Java: más misiones de demanda, luego más reciente.
- Misiones lanzadas: `PRODUCTION`, propiedad `launchedBy: 'orchestrator'`; software → `TEAM-ENGINEERING`, servicio → `TEAM-CREATIVE-PRODUCT-INTELLIGENCE`; `BUILT_BY` la misión.
- Actor en cambios de producto: `orchestrator`. Nunca mueve un producto `PAUSED`/`RETIRED` (→ `STOPPED`).
- Correo al arrancar, elegir (con motivo), listo, fallido; eventos `EMPRESA_ORCHESTRATOR_*`; chat 100% Java.

## Review Focus

- Dos `tick()` concurrentes (el programado y el de fin de misión) → no lanzan dos misiones: `tick` es `synchronized` y cada paso se persiste antes de lanzar (test Task 3).
- La misión de construcción se borra a mano durante `BUILDING` → el ciclo pasa a `FAILED` con el motivo, no queda colgado (test Task 3).
- Alex devuelve una ficha con precio 0 y sin "a cotizar" → la ficha se guarda pero el producto no llega a listo; `BUILDING` pide ronda con "falta el precio" (test Task 3).
- Reinicio en medio de `BUILDING` con la misión ya terminada → el siguiente tick la ve terminada y sigue (test Task 3, por estado).
- Orquestador pausado con una ronda pendiente → no pide la ronda hasta reanudar (test Task 3).

---

### Task 1: Ciclo persistente y policies

**Files:** Create `model/OrchestratorStatus.java`, `model/OrchestratorRun.java`, `model/OrchestratorStep.java`, `service/OrchestratorMemoryService.java`; Modify `model/PolicyKey.java`, `service/CompanyPolicyService.java` (defaults), `service/CompanyMemoryService.java` (constraint `orchestrator_run_id`), `service/MissionMemoryService.java` (`markLaunchedBy(missionId, who)`), `frontend/src/api/types.ts` + `SettingsPage.tsx` (etiquetas); Test `service/CompanyPolicyServiceTest` o equivalente (defaults presentes).

**Interfaces (Produces):**
- `enum OrchestratorStatus { CHOOSING, DISCOVERING, PROPOSING, BUILDING, READY, FAILED, STOPPED; boolean active() }`.
- `record OrchestratorRun(String id, OrchestratorStatus status, String productId, String discoveryMissionId, String buildMissionId, String choiceReason, Instant startedAt, Instant updatedAt, String failureReason)`.
- `record OrchestratorStep(Instant at, String step, String detail)`.
- `OrchestratorMemoryService`: `OrchestratorRun create()`, `List<OrchestratorRun> active()`, `Optional<OrchestratorRun> latest()`, `void save(OrchestratorRun)`, `void addStep(String runId, String step, String detail)`, `List<OrchestratorStep> steps(String runId)`.

- [ ] Test que falla: `CompanyPolicyService` siembra `ORCHESTRATOR_ENABLED=1` y `MAX_AUTONOMOUS_PRODUCTS=1` (por el mapa de defaults: exponer `static Map<PolicyKey, Double> defaultsFor(AppProperties)` o testear vía `ensureDefaultPolicies` con driver mockeado, según lo que ya haga el test existente); `OrchestratorStatus.active()` verdadero solo en los 4 primeros → FAIL → implementar → PASS → suite → commit `Orquestador: ciclo persistente y policies`.

---

### Task 2: Decisiones estructuradas de Alex

**Files:** Modify `service/CeoService.java`; Create `model/ProductChoice.java`, `model/ProductSheet.java`; Test `service/CeoServiceOrchestratorTest.java`.

**Interfaces:** `record ProductChoice(String productId, String reason)`; `record ProductSheet(String kind, String targetCustomer, List<String> markets, List<String> languages, Double priceUsd, Boolean priceOnRequest, Double estimatedCostUsd, String delivery)`; `ProductChoice chooseProduct(String candidatesText, String model)`, `ProductSheet proposeProductSheet(String productText, String evidenceText, String model)`, `String summarizeDelivery(String productText, String creativeResults, String model)` — todas con `format` (schema) y `callStructured`, actor `ceo`, prompts en español anti-alucinación ("usa solo los datos dados; si no hay precio, priceOnRequest=true").

- [ ] Tests que fallan (remoto mockeado como en `CeoServiceInvestorFeedbackTest`): parsea `{productId, reason}`; parsea la ficha con listas; `summarizeDelivery` devuelve el texto de `{delivery}`; un JSON inválido lanza `IllegalStateException` → implementar → PASS → suite → commit `CeoService: elegir producto, completar su ficha y resumir la entrega`.

---

### Task 3: `ProductOrchestrator` (máquina de estados)

**Files:** Create `service/ProductOrchestrator.java`; Test `service/ProductOrchestratorTest.java`.

**Interfaces (Consumes):** T1, T2, `ProductService` (`list`, `view`, `update`, `changeStatus`, `linkMissions`), `ProductMemoryService.evidence`, `MissionService.start(id, instruction, "PRODUCTION", null, teamId)` y `recordDecision(id, new DecisionCommand(REQUEST_MORE_EVIDENCE, reason))`, `MissionMemoryService.find/evidenceRound/tasks`, `CompanyPolicyService.activeValue`, `CompanyMemoryService.agentModel("ceo", …)`, `CompanyEventPublisher`, `AlertMailService`.
**Produces:** `synchronized void tick()`; `void onMissionFinished(String missionId)` (= `tick()`); `Optional<OrchestratorView> current()` con `record OrchestratorView(OrchestratorRun run, List<OrchestratorStep> steps, boolean enabled)`.

- [ ] **Step 1: Tests que fallan** (todo mockeado):
  - No arranca con un producto `READY_TO_SELL` o `IN_CONSTRUCTION`, ni con `ORCHESTRATOR_ENABLED = 0`, ni con 1 ciclo activo y límite 1.
  - Arranca con solo ideas: crea el ciclo, correo "arrancó", evento `EMPRESA_ORCHESTRATOR_STARTED`.
  - CHOOSING: idea del fundador con evidencia gana sin `chooseProduct`; sin candidatos → `MissionService.start` de discovery y estado `DISCOVERING`; Alex elige un id fuera de la lista → gana el de la regla Java; `chooseProduct` lanza → regla Java; la elección se guarda con motivo y correo.
  - DISCOVERING: la misión en `AWAITING_INVESTOR` → vuelve a `CHOOSING`; `FAILED` → ciclo `FAILED`.
  - PROPOSING: aplica la ficha con `update(…, "orchestrator")`, `changeStatus(IN_CONSTRUCTION, …, "orchestrator")`, lanza Engineering para `SOFTWARE` y Creative para `SERVICE`, `linkMissions(id, null, [misión])`, estado `BUILDING` persistido **antes** de lanzar (idempotencia: si ya hay `buildMissionId`, no lanza otra).
  - BUILDING: misión en curso → nada; terminada y producto `READY_TO_SELL` → `READY` + correo; terminada sin cumplir y con rondas → `recordDecision(REQUEST_MORE_EVIDENCE, "Falta: …")`; sin rondas → `FAILED`; servicio → `summarizeDelivery` y `update(delivery)` antes de evaluar; misión inexistente → `FAILED` "la misión de construcción ya no existe".
  - Producto `PAUSED`/`RETIRED` en cualquier paso → `STOPPED`; orquestador apagado → `tick` no avanza nada.
- [ ] **Step 2:** → FAIL. **Step 3:** implementar (un paso por tick; `save` + `addStep` en cada transición; `evaluate` llama a `ProductAutomation.buildFinished` si el producto sigue en `IN_CONSTRUCTION`). **Step 4:** → PASS; suite. **Step 5:** commit `ProductOrchestrator: ciclo autónomo elegir → ficha → construir → listo`.

---

### Task 4: Programación y enganche con las misiones

**Files:** Modify `ProductOrchestrator` (`@Scheduled(fixedDelay = 900_000, initialDelay = 120_000)`), `MissionExecutor` (setter opcional `setProductOrchestrator`; al pasar a `AWAITING_INVESTOR` o `FAILED`, `onMissionFinished` dentro del mismo try/catch que el catálogo, en un hilo aparte del orquestador de misiones para no bloquear); Test `MissionExecutorTest`.

- [ ] Test: una misión que termina llama `onMissionFinished(missionId)`; si lanza, la misión igual queda `AWAITING_INVESTOR` → implementar → PASS → suite → commit `Orquestador: chequeo cada 15 minutos y al terminar cada misión`.

---

### Task 5: Chat y API

**Files:** Modify `ChatIntentRouter.java` (+`ProductOrchestrator`), Create `controller/OrchestratorController.java` (`GET /api/company/orchestrator` → `current()`); Tests `ChatIntentRouterTest`, `OrchestratorControllerTest`.

- [ ] Tests: "¿qué está haciendo el orquestador?" → paso, producto, misiones y motivo; sin ciclo → "no hay ningún ciclo en curso" y si está encendido; "dame un status" agrega "Orquestador: …"; "pausa el orquestador" / "reanuda el orquestador" → `CompanyPolicyService.createVersion(ORCHESTRATOR_ENABLED, 0|1, mensaje)` y confirmación (gobernanza, antes de las menciones) → implementar → PASS → suite → commit `Chat: estado del orquestador y pausar/reanudar`.

---

### Task 6: Command Center

**Files:** Modify `frontend/src/pages/ProductsPage.tsx` (panel "Orquestador": estado, producto, misiones con link, motivo, pasos), `api/types.ts`, `api/client.ts`.

- [ ] `npm run lint && npm run build` sin errores nuevos; `mvn -q test` verde; commit `Command Center: ciclo del orquestador en Productos`.

---

### Task 7: Documentación y verificación en vivo

- [ ] `CLAUDE.md`, `docs/EVENTS.md` (`EMPRESA_ORCHESTRATOR_*`), `docs/HISTORY.md`.
- [ ] En vivo, **avisando al fundador antes** de retirar el producto de prueba "Prueba de catálogo (verificación)" (hoy `READY_TO_SELL`) y de dejar el orquestador encendido: con las ideas existentes, ver `CHOOSING` → elección con motivo → ficha → misión de construcción real → `READY` o `FAILED` explicado; chat y correo en cada paso. Si kimi-k3 sigue caído, Engineering usa el suplente (verificado en la spec de salud de modelos).
- [ ] Commit `Documentar el orquestador y su verificación en vivo`.

## Self-review

- Cobertura: §1 → T1; §2 → T3/T4; §3 → T2/T3; §4 → T3 (correo, eventos), T5 (chat), T6 (pantalla), T1 (policies en Settings).
- Tipos: `OrchestratorRun/Status/Step` (T1) en T3/T5/T6; `ProductChoice/ProductSheet` (T2) en T3; `tick/onMissionFinished/current` (T3) en T4/T5.

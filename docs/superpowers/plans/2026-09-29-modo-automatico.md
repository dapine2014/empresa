# Modo automático — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Interruptores de modo automático (todo / productos / clientes "próximamente") y panel "qué están haciendo" arriba del Dashboard, consultables y operables también desde el chat.

**Architecture:** Un `AutonomyService` nuevo es la única puerta para leer y cambiar el modo automático: lee y escribe la policy versionada `ORCHESTRATOR_ENABLED` (no hay estado nuevo) y suma los conteos de "esperando tu decisión". `AutonomyController` lo expone (`GET`/`PUT /api/company/autonomy`); `ChatIntentRouter` lo usa para la consulta y los comandos. El frontend agrega un componente `AutonomyPanel` arriba de `DashboardPage` que combina `/autonomy`, `/orchestrator` y `/agents/status`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Neo4j driver plano (Cypher a mano), JUnit 5 + Mockito; React + Vite + TS, `@tanstack/react-query`, oxlint.

**Spec:** `docs/superpowers/specs/2026-09-29-modo-automatico-design.md`

## Global Constraints

- Una sola fuente de verdad: la policy `ORCHESTRATOR_ENABLED` (0/1). Nada de estado nuevo en Neo4j para los interruptores.
- Encender algo encendido (o apagar algo apagado) **no** crea versión de policy.
- Motivo de la versión: `"Encendido desde el Dashboard"`, `"Apagado desde el Dashboard"`, `"Encendido desde el chat"`, `"Apagado desde el chat"`.
- `clients` en el `PUT` mientras la búsqueda de clientes no existe → `IllegalArgumentException` ("La búsqueda de clientes todavía no existe (próximamente).").
- Errores HTTP: convención del proyecto (`IllegalArgumentException` → 500 con mensaje); no agregar `@ExceptionHandler`.
- Chat: consulta y comandos resueltos en Java, sin modelo; comandos en la gobernanza (antes de las menciones), junto a `ORCHESTRATOR_COMMAND`.
- Leyenda fija en la UI: "Contactar clientes o vender sigue siendo decisión tuya."
- Polling cada 15 s; sin WebSocket/SSE. Sin estado optimista en los interruptores.
- `api/types.ts` refleja a mano los records Java.
- Jackson 3 (`tools.jackson.*`) si hiciera falta; no `com.fasterxml`.

## Review Focus

- "Todo en automático" con productos ya encendido: no debe crear versión ni fallar (test en Task 1: `turningOnWhatIsAlreadyOnCreatesNoVersion`).
- Orquestador pausado solo (corte por fallos): el panel y el chat muestran el motivo, y encender desde el botón lo reanuda (test en Task 1: `thePauseReasonIsShownOnlyWhenOff`; en vivo en Task 5).
- "pausa el orquestador" y "apaga el automático" no se pisan: cada frase va a su comando (test en Task 3: `autonomyCommandsDoNotCaptureOrchestratorCommands`).
- Una frase de arranque de misión que menciona "automático" (p. ej. "inicia una misión sobre automatización") no debe tomarse como consulta de modo automático (test en Task 3: `aMissionAboutAutomationIsNotTheAutonomyQuery`).
- El PUT con cuerpo vacío `{}` no cambia nada y devuelve la vista (test en Task 2: `anEmptyCommandChangesNothing`).

---

### Task 1: `AutonomyService` (vista + cambio) y conteo de misiones del orquestador

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/AutonomyService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java` (agregar `countAwaitingLaunchedBy` después de `markLaunchedBy`)
- Test: `app/src/test/java/com/aicompany/core/service/AutonomyServiceTest.java`

**Interfaces:**
- Consumes: `CompanyPolicyService.activeValue(PolicyKey)`, `.snapshot(PolicyKey)` → `PolicySnapshot(key, activeVersion, activeValue, createdBy, changeReason, updatedAt, history)`, `.createVersion(PolicyKey, double, String)`; `DependencyMemoryService.list()` → `List<Map<String,Object>>` con clave `"status"`.
- Produces:
  - `MissionMemoryService.countAwaitingLaunchedBy(String who)` → `int`
  - `AutonomyService.Front(boolean enabled, boolean available, String pauseReason)`
  - `AutonomyService.Waiting(int orchestratorMissions, int pendingDependencies)`
  - `AutonomyService.AutonomyView(Front products, Front clients, Waiting waiting)`
  - `AutonomyService.AutonomyCommand(Boolean products, Boolean clients)`
  - `AutonomyService.view()` → `AutonomyView`
  - `AutonomyService.setProducts(boolean on, String origin)` → `boolean` (true si cambió)
  - `AutonomyService.update(AutonomyCommand command, String origin)` → `AutonomyView`

- [ ] **Step 1: Escribir los tests que fallan**

```java
package com.aicompany.core.service;

import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.PolicySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec modo automático (2026-09-29): interruptores sobre la policy versionada y "esperando tu decisión". */
class AutonomyServiceTest {

    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final MissionMemoryService missionMemory = mock(MissionMemoryService.class);
    private final DependencyMemoryService dependencies = mock(DependencyMemoryService.class);
    private final AutonomyService service = new AutonomyService(policies, missionMemory, dependencies);

    {
        when(dependencies.list()).thenReturn(List.of());
    }

    private void productsOn(boolean on, String reason) {
        when(policies.activeValue(PolicyKey.ORCHESTRATOR_ENABLED)).thenReturn(on ? 1.0 : 0.0);
        when(policies.snapshot(PolicyKey.ORCHESTRATOR_ENABLED)).thenReturn(new PolicySnapshot(
                "ORCHESTRATOR_ENABLED", 2, on ? 1.0 : 0.0, "human", reason, Instant.now(), List.of()));
    }

    @Test
    void turningProductsOnCreatesAVersionWithTheOrigin() {
        productsOn(false, "Pausado por el fundador desde el chat");

        assertTrue(service.setProducts(true, "el Dashboard"));

        verify(policies).createVersion(PolicyKey.ORCHESTRATOR_ENABLED, 1, "Encendido desde el Dashboard");
    }

    @Test
    void turningProductsOffCreatesAVersionWithTheOrigin() {
        productsOn(true, "seed");

        assertTrue(service.setProducts(false, "el chat"));

        verify(policies).createVersion(PolicyKey.ORCHESTRATOR_ENABLED, 0, "Apagado desde el chat");
    }

    @Test
    void turningOnWhatIsAlreadyOnCreatesNoVersion() {
        productsOn(true, "seed");

        assertFalse(service.setProducts(true, "el Dashboard"));

        verify(policies, never()).createVersion(any(), anyDouble(), anyString());
    }

    @Test
    void clientsCannotBeTurnedOnYet() {
        productsOn(true, "seed");

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(new AutonomyService.AutonomyCommand(null, true), "el Dashboard"));
        assertTrue(ex.getMessage().contains("próximamente"), ex.getMessage());
        verify(policies, never()).createVersion(any(), anyDouble(), anyString());
    }

    @Test
    void thePauseReasonIsShownOnlyWhenOff() {
        productsOn(false, "Pausado solo: 2 ciclos seguidos fallidos");
        assertEquals("Pausado solo: 2 ciclos seguidos fallidos", service.view().products().pauseReason());

        productsOn(true, "Encendido desde el Dashboard");
        assertNull(service.view().products().pauseReason());
    }

    @Test
    void theViewCountsWhatWaitsForTheFounderAndClientsAreNotAvailable() {
        productsOn(true, "seed");
        when(missionMemory.countAwaitingLaunchedBy("orchestrator")).thenReturn(4);
        when(dependencies.list()).thenReturn(List.of(
                Map.<String, Object>of("status", "PENDING_APPROVAL"), Map.<String, Object>of("status", "APPROVED"),
                Map.<String, Object>of("status", "PENDING_APPROVAL")));

        var view = service.view();

        assertTrue(view.products().enabled());
        assertTrue(view.products().available());
        assertFalse(view.clients().enabled());
        assertFalse(view.clients().available());
        assertEquals(4, view.waiting().orchestratorMissions());
        assertEquals(2, view.waiting().pendingDependencies());
    }

    @Test
    void updateAppliesProductsAndReturnsTheView() {
        productsOn(false, "x");

        service.update(new AutonomyService.AutonomyCommand(true, null), "el Dashboard");

        verify(policies).createVersion(PolicyKey.ORCHESTRATOR_ENABLED, 1, "Encendido desde el Dashboard");
    }
}
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest=AutonomyServiceTest`
Expected: error de compilación (`AutonomyService` no existe).

- [ ] **Step 3: Implementar**

En `MissionMemoryService.java`, después de `markLaunchedBy`:

```java
    /** Spec modo automático (2026-09-29): misiones lanzadas por {@code who} que esperan la decisión del fundador. */
    public int countAwaitingLaunchedBy(String who) {
        try (var session = driver.session()) {
            return session.run("MATCH (m:Mission {status:'AWAITING_INVESTOR', launchedBy:$who}) RETURN count(m) AS n",
                    Map.of("who", who)).single().get("n").asInt();
        }
    }
```

`AutonomyService.java`:

```java
package com.aicompany.core.service;

import com.aicompany.core.model.PolicyKey;
import org.springframework.stereotype.Service;

/**
 * Spec modo automático (2026-09-29): los interruptores son la policy versionada {@code ORCHESTRATOR_ENABLED} (una sola
 * fuente de verdad con Settings y el chat). "Buscar clientes" queda como próximamente hasta la entrega B.
 */
@Service
public class AutonomyService {

    public record Front(boolean enabled, boolean available, String pauseReason) {
    }

    public record Waiting(int orchestratorMissions, int pendingDependencies) {
    }

    public record AutonomyView(Front products, Front clients, Waiting waiting) {
    }

    /** Campos null = no se tocan. {@code clients} está reservado para la búsqueda de prospectos. */
    public record AutonomyCommand(Boolean products, Boolean clients) {
    }

    private final CompanyPolicyService policies;
    private final MissionMemoryService missionMemory;
    private final DependencyMemoryService dependencies;

    public AutonomyService(CompanyPolicyService policies, MissionMemoryService missionMemory,
                           DependencyMemoryService dependencies) {
        this.policies = policies;
        this.missionMemory = missionMemory;
        this.dependencies = dependencies;
    }

    public AutonomyView view() {
        var on = productsOn();
        String reason = null;
        if (!on) {
            var policy = policies.snapshot(PolicyKey.ORCHESTRATOR_ENABLED);
            reason = policy == null ? null : policy.changeReason();
        }
        var pending = (int) dependencies.list().stream().filter(d -> "PENDING_APPROVAL".equals(d.get("status"))).count();
        return new AutonomyView(new Front(on, true, reason), new Front(false, false, null),
                new Waiting(missionMemory.countAwaitingLaunchedBy("orchestrator"), pending));
    }

    /** Devuelve true si cambió; sin cambio no crea versión (el historial no se llena de ruido). */
    public boolean setProducts(boolean on, String origin) {
        if (productsOn() == on) {
            return false;
        }
        policies.createVersion(PolicyKey.ORCHESTRATOR_ENABLED, on ? 1 : 0,
                (on ? "Encendido" : "Apagado") + " desde " + origin);
        return true;
    }

    public AutonomyView update(AutonomyCommand command, String origin) {
        if (command.clients() != null) {
            throw new IllegalArgumentException("La búsqueda de clientes todavía no existe (próximamente).");
        }
        if (command.products() != null) {
            setProducts(command.products(), origin);
        }
        return view();
    }

    private boolean productsOn() {
        return policies.activeValue(PolicyKey.ORCHESTRATOR_ENABLED) >= 1;
    }
}
```

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn -q test -Dtest=AutonomyServiceTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/AutonomyService.java app/src/main/java/com/aicompany/core/service/MissionMemoryService.java app/src/test/java/com/aicompany/core/service/AutonomyServiceTest.java
git commit -m "AutonomyService: interruptores del modo automático sobre la policy versionada"
```

---

### Task 2: `AutonomyController` (`GET`/`PUT /api/company/autonomy`)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/controller/AutonomyController.java`
- Test: `app/src/test/java/com/aicompany/core/controller/AutonomyControllerTest.java`

**Interfaces:**
- Consumes: `AutonomyService.view()`, `AutonomyService.update(AutonomyCommand, String)` (Task 1).
- Produces: `GET /api/company/autonomy` → `AutonomyView`; `PUT /api/company/autonomy` body `{products?: boolean, clients?: boolean}` → `AutonomyView`.

- [ ] **Step 1: Escribir los tests que fallan**

```java
package com.aicompany.core.controller;

import com.aicompany.core.service.AutonomyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutonomyControllerTest {

    private final AutonomyService service = mock(AutonomyService.class);
    private final AutonomyController controller = new AutonomyController(service);
    private final AutonomyService.AutonomyView view = new AutonomyService.AutonomyView(
            new AutonomyService.Front(true, true, null), new AutonomyService.Front(false, false, null),
            new AutonomyService.Waiting(0, 0));

    // Spec modo automático (2026-09-29): el Dashboard lee y cambia los interruptores.
    @Test
    void getReturnsTheView() {
        when(service.view()).thenReturn(view);

        assertSame(view, controller.view());
    }

    @Test
    void putAppliesTheCommandFromTheDashboard() {
        var command = new AutonomyService.AutonomyCommand(true, null);
        when(service.update(command, "el Dashboard")).thenReturn(view);

        assertSame(view, controller.update(command));
    }

    @Test
    void anEmptyCommandChangesNothing() {
        var command = new AutonomyService.AutonomyCommand(null, null);
        when(service.update(command, "el Dashboard")).thenReturn(view);

        assertSame(view, controller.update(command));
        verify(service).update(command, "el Dashboard");
    }
}
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest=AutonomyControllerTest`
Expected: error de compilación (`AutonomyController` no existe).

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.controller;

import com.aicompany.core.service.AutonomyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Spec modo automático (2026-09-29): interruptores del Dashboard y conteos de "esperando tu decisión". */
@RestController
@RequestMapping("/api/company/autonomy")
public class AutonomyController {

    private final AutonomyService service;

    public AutonomyController(AutonomyService service) {
        this.service = service;
    }

    @GetMapping
    public AutonomyService.AutonomyView view() {
        return service.view();
    }

    @PutMapping
    public AutonomyService.AutonomyView update(@RequestBody AutonomyService.AutonomyCommand command) {
        return service.update(command, "el Dashboard");
    }
}
```

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn -q test -Dtest=AutonomyControllerTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/controller/AutonomyController.java app/src/test/java/com/aicompany/core/controller/AutonomyControllerTest.java
git commit -m "AutonomyController: GET y PUT del modo automático"
```

---

### Task 3: Chat — consulta "¿qué está en automático?" y comandos

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
  - constructor (`:205-245`): nuevo parámetro final `AutonomyService autonomy`
  - patrón nuevo junto a `ORCHESTRATOR_COMMAND` (`:196`)
  - `isGovernance` (`:297`) y `resolve` (`:504`): el comando nuevo
  - `enum QueryIntent` (`:1135`): `AUTONOMY`
  - `detectQuery` (`:1200`, antes del bloque `orquestador`): detección
  - switch de topics (`:1365`): `case "AUTONOMY" -> formatAutonomy();`
  - métodos nuevos `handleAutonomyCommand` y `formatAutonomy` junto a `handleOrchestratorCommand`
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java` (mock + constructor `:57-62`, tests nuevos junto a los del orquestador `:1748`)

**Interfaces:**
- Consumes: `AutonomyService.view()`, `.setProducts(boolean, String)` (Task 1); `formatOrchestrator()` existente.
- Produces: nada que usen tareas posteriores.

- [ ] **Step 1: Escribir los tests que fallan**

En `ChatIntentRouterTest`: agregar el mock y pasarlo al final del constructor.

```java
    private final AutonomyService autonomy = mock(AutonomyService.class);
```

```java
    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory, companyMemory,
            conversationMemory, companyPolicyService, customerService, productStatusService,
            "qwen2.5-coder:14b", teamMemory, promptMemory, finance, dependencies, products, modelHealth,
            orchestrator, apiKeys, autonomy
    );
```

Tests nuevos (junto a `aPausedOrchestratorSaysWhy`):

```java
    // Spec modo automático (2026-09-29): el fundador consulta y cambia el modo automático desde el chat.
    private void autonomyView(boolean productsOn, String reason) {
        when(autonomy.view()).thenReturn(new AutonomyService.AutonomyView(
                new AutonomyService.Front(productsOn, true, reason), new AutonomyService.Front(false, false, null),
                new AutonomyService.Waiting(3, 1)));
    }

    @Test
    void theAutonomyQueryShowsEachFrontAndWhatWaitsForTheFounder() {
        autonomyView(false, "Pausado solo: 2 ciclos seguidos fallidos");

        var response = router.route("¿qué está en automático?");

        assertTrue(response.contains("Crear productos y servicios: apagado"), response);
        assertTrue(response.contains("2 ciclos seguidos fallidos"), response);
        assertTrue(response.contains("Buscar clientes: próximamente"), response);
        assertTrue(response.contains("3 misiones del orquestador"), response);
        assertTrue(response.contains("1 dependencia"), response);
        verifyNoInteractions(ceoService);
    }

    @Test
    void turnEverythingOnFromTheChat() {
        when(autonomy.setProducts(true, "el chat")).thenReturn(true);

        var response = router.route("pon todo en automático");

        verify(autonomy).setProducts(true, "el chat");
        assertTrue(response.contains("encendido"), response);
        assertTrue(response.contains("próximamente"), response);
        verifyNoInteractions(ceoService);
    }

    @Test
    void turnEverythingOffFromTheChat() {
        when(autonomy.setProducts(false, "el chat")).thenReturn(false);

        var response = router.route("apaga el automático");

        verify(autonomy).setProducts(false, "el chat");
        assertTrue(response.contains("ya estaba apagado"), response);
    }

    @Test
    void autonomyCommandsDoNotCaptureOrchestratorCommands() {
        router.route("pausa el orquestador");

        verify(companyPolicyService).createVersion(eq(PolicyKey.ORCHESTRATOR_ENABLED), eq(0.0), anyString());
        verify(autonomy, never()).setProducts(anyBoolean(), anyString());
    }

    @Test
    void aMissionAboutAutomationIsNotTheAutonomyQuery() {
        router.route("inicia una misión para investigar servicios de automatización para pymes");

        verify(autonomy, never()).view();
    }
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest=ChatIntentRouterTest`
Expected: error de compilación (el constructor no acepta `AutonomyService`).

- [ ] **Step 3: Implementar**

Campo y constructor (último parámetro, `this.autonomy = autonomy;`):

```java
    private final AutonomyService autonomy;
```

Patrón, debajo de `ORCHESTRATOR_COMMAND` (el texto llega normalizado: minúsculas y sin tildes):

```java
    /** Spec modo automático (2026-09-29): comandos del fundador, en la gobernanza junto al orquestador. */
    private static final Pattern AUTONOMY_COMMAND = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?(pon todo en automatico|activa el modo automatico"
                    + "|apaga el (?:modo )?automatico|desactiva el modo automatico)\\s*[.!]?\\s*$");
```

`isGovernance`: agregar `AUTONOMY_COMMAND.matcher(normalize(message)).matches() ||` al principio del `return`.

`resolve`, justo después del bloque de `orchestratorCommand`:

```java
        var autonomyCommand = AUTONOMY_COMMAND.matcher(normalize(message));
        if (autonomyCommand.matches()) {
            var command = autonomyCommand.group(1);
            return handleAutonomyCommand(command.startsWith("pon") || command.startsWith("activa"));
        }
```

`enum QueryIntent`: agregar `AUTONOMY,` antes de `ORCHESTRATOR,`.

`detectQuery`, antes de `if (normalized.contains("orquestador"))`:

```java
        // Spec modo automático (2026-09-29): "¿qué está en automático?", "modo automático".
        if (normalized.matches(".*\\b(modo automatico|en automatico)\\b.*")) {
            return new QueryMatch(QueryIntent.AUTONOMY, null);
        }
```

Switch de topics: `case "AUTONOMY" -> formatAutonomy();` antes de `case "ORCHESTRATOR"`.

Métodos, después de `handleOrchestratorCommand`:

```java
    private String handleAutonomyCommand(boolean on) {
        var changed = autonomy.setProducts(on, "el chat");
        log.info("CHAT_INTENT_AUTONOMY_{} changed={}", on ? "ON" : "OFF", changed);
        var products = changed
                ? "Crear productos y servicios: " + (on ? "encendido (retoma en el próximo chequeo, cada 15 minutos o al "
                        + "terminar una misión)" : "apagado (las misiones ya lanzadas terminan igual)")
                : "Crear productos y servicios: ya estaba " + (on ? "encendido" : "apagado");
        return "Modo automático — " + products + ". Buscar clientes: próximamente (todavía no existe). "
                + "Contactar clientes o vender sigue siendo decisión tuya.";
    }

    /** Spec modo automático (2026-09-29): cada frente, qué hace el orquestador y lo que espera al fundador, en Java. */
    private String formatAutonomy() {
        var view = autonomy.view();
        var products = view.products().enabled() ? "encendido"
                : "apagado" + (view.products().pauseReason() == null ? "" : " (" + view.products().pauseReason() + ")");
        var waiting = view.waiting();
        return "Modo automático — Crear productos y servicios: " + products
                + ". Buscar clientes: próximamente (todavía no existe). " + formatOrchestrator()
                + " Esperando tu decisión: " + waiting.orchestratorMissions() + " misiones del orquestador y "
                + waiting.pendingDependencies() + (waiting.pendingDependencies() == 1 ? " dependencia pendiente."
                        : " dependencias pendientes.")
                + " Contactar clientes o vender sigue siendo decisión tuya.";
    }
```

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn -q test -Dtest=ChatIntentRouterTest`
Expected: PASS (todos, incluidos los 5 nuevos).

- [ ] **Step 5: Suite completa**

Run: `cd app && mvn test 2>&1 | grep -E "Tests run:.*Fail|BUILD" | tail -2`
Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: consulta del modo automático y comandos para encenderlo o apagarlo"
```

---

### Task 4: Frontend — `AutonomyPanel` arriba del Dashboard

**Files:**
- Create: `app/frontend/src/orchestratorLabels.ts` (etiquetas compartidas, movidas desde `ProductsPage.tsx:182-190`)
- Create: `app/frontend/src/components/AutonomyPanel.tsx`
- Modify: `app/frontend/src/api/types.ts` (tipos nuevos, al final del bloque del orquestador `:453-460`)
- Modify: `app/frontend/src/api/client.ts` (dos entradas en `api`, junto a `orchestrator` `:82`)
- Modify: `app/frontend/src/pages/ProductsPage.tsx` (importar las etiquetas en vez de definirlas)
- Modify: `app/frontend/src/pages/DashboardPage.tsx` (render de `<AutonomyPanel />` debajo del `<h1>`)
- Modify: `app/frontend/src/index.css` (estilos del panel, al final)

**Interfaces:**
- Consumes: `GET`/`PUT /api/company/autonomy` (Task 2), `api.orchestrator`, `api.agentsStatus` existentes.
- Produces: `AutonomyView`, `AutonomyFront`, `AutonomyCommand` en `types.ts`; `api.autonomy()`, `api.setAutonomy(command)`; `ORCHESTRATOR_LABELS` exportado.

- [ ] **Step 1: Tipos y cliente**

`types.ts`:

```ts
// Modo automático (spec 2026-09-29): reflejo a mano de AutonomyService.AutonomyView/AutonomyCommand.
export interface AutonomyFront {
  enabled: boolean
  available: boolean
  pauseReason: string | null
}

export interface AutonomyView {
  products: AutonomyFront
  clients: AutonomyFront
  waiting: { orchestratorMissions: number; pendingDependencies: number }
}

export interface AutonomyCommand {
  products?: boolean
  clients?: boolean
}
```

`client.ts` (importar `AutonomyView`, `AutonomyCommand` en el import de tipos existente):

```ts
  autonomy: () => request<AutonomyView>('/api/company/autonomy'),
  setAutonomy: (command: AutonomyCommand) =>
    request<AutonomyView>('/api/company/autonomy', { method: 'PUT', body: JSON.stringify(command) }),
```

- [ ] **Step 2: Etiquetas compartidas**

`orchestratorLabels.ts`:

```ts
import type { OrchestratorStatus } from './api/types'

// Mismas etiquetas en Productos y en el Dashboard (modo automático).
export const ORCHESTRATOR_LABELS: Record<OrchestratorStatus, string> = {
  CHOOSING: 'Eligiendo qué construir',
  DISCOVERING: 'Buscando ideas (discovery)',
  PROPOSING: 'Completando la ficha',
  BUILDING: 'Construyendo',
  READY: 'Listo para vender',
  FAILED: 'Falló',
  STOPPED: 'Detenido',
}
```

En `ProductsPage.tsx`: borrar la constante local, agregar `import { ORCHESTRATOR_LABELS } from '../orchestratorLabels'` y quitar `OrchestratorStatus` del import de tipos si ya no se usa en el archivo (el build corre `tsc -b` y un import sin uso lo rompe).

- [ ] **Step 3: Componente**

`components/AutonomyPanel.tsx`:

```tsx
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AutonomyCommand } from '../api/types'
import { ORCHESTRATOR_LABELS } from '../orchestratorLabels'

// Modo automático (spec 2026-09-29): interruptores + "qué están haciendo". Sin estado optimista: el interruptor
// muestra lo que devuelve el backend; si el cambio falla, queda como estaba y se ve el motivo.
export default function AutonomyPanel() {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const autonomy = useQuery({ queryKey: ['autonomy'], queryFn: api.autonomy, refetchInterval: 15_000 })
  const orchestrator = useQuery({ queryKey: ['orchestrator'], queryFn: api.orchestrator, refetchInterval: 15_000 })
  const agents = useQuery({ queryKey: ['agentsStatus'], queryFn: api.agentsStatus, refetchInterval: 5_000 })
  const mutation = useMutation({
    mutationFn: (command: AutonomyCommand) => api.setAutonomy(command),
    onSuccess: (view) => {
      setError(null)
      queryClient.setQueryData(['autonomy'], view)
      void queryClient.invalidateQueries({ queryKey: ['orchestrator'] })
    },
    onError: (e: Error) => setError(e.message),
  })

  const view = autonomy.data
  if (!view) {
    return <div className="card autonomy">{autonomy.isError ? 'Modo automático no disponible.' : 'Cargando…'}</div>
  }
  const available = [view.products, view.clients].filter((f) => f.available)
  const allOn = available.every((f) => f.enabled)
  const someOn = available.some((f) => f.enabled)
  const run = orchestrator.data?.run
  const active = run && ['CHOOSING', 'DISCOVERING', 'PROPOSING', 'BUILDING'].includes(run.status)
  const missionId = run?.buildMissionId ?? run?.discoveryMissionId
  const working = (agents.data ?? []).filter((a) => a.status === 'WORKING')

  return (
    <div className="card autonomy">
      <h2>Modo automático</h2>
      <div className="autonomy-switches">
        <label>
          <input
            type="checkbox"
            checked={allOn}
            ref={(el) => {
              if (el) el.indeterminate = someOn && !allOn
            }}
            disabled={mutation.isPending}
            onChange={() => mutation.mutate({ products: !allOn })}
          />{' '}
          <strong>Todo en automático</strong> {someOn && !allOn && <span className="hint">(parcial)</span>}
        </label>
        <label>
          <input
            type="checkbox"
            checked={view.products.enabled}
            disabled={mutation.isPending}
            onChange={() => mutation.mutate({ products: !view.products.enabled })}
          />{' '}
          Crear productos y servicios
        </label>
        <label className="hint">
          <input type="checkbox" checked={false} disabled /> Buscar clientes (próximamente)
        </label>
      </div>
      {error && <p className="error">{error}</p>}
      {!view.products.enabled && view.products.pauseReason && <p className="error">{view.products.pauseReason}</p>}
      {view.products.enabled && !active && (
        <p className="hint">Retoma en el próximo chequeo (cada 15 minutos o al terminar una misión).</p>
      )}
      <p className="hint">Contactar clientes o vender sigue siendo decisión tuya.</p>

      <h3>Qué están haciendo</h3>
      <div className="autonomy-front">
        <strong>Productos y servicios:</strong>{' '}
        {orchestrator.isError ? (
          'no disponible'
        ) : active && run ? (
          <>
            {ORCHESTRATOR_LABELS[run.status]}
            {missionId && (
              <>
                {' · '}
                <Link to={`/missions/${missionId}`}>{missionId}</Link>
              </>
            )}
            <ul className="autonomy-steps">
              {(orchestrator.data?.steps ?? []).slice(-5).map((s, i) => (
                <li key={i}>
                  {new Date(s.at).toLocaleTimeString()} — {s.detail}
                </li>
              ))}
            </ul>
          </>
        ) : !view.products.enabled ? (
          'pausado'
        ) : (
          'sin ciclo en curso (arranca solo cuando no hay productos listos para vender ni en construcción)'
        )}
      </div>
      <div className="autonomy-front">
        <strong>Clientes:</strong> próximamente
      </div>
      <div className="autonomy-front">
        <strong>Agentes trabajando ahora:</strong>{' '}
        {working.length === 0
          ? 'ninguno'
          : working.map((a) => `${a.name} (${a.action ?? '—'}${a.missionId ? ` · ${a.missionId}` : ''})`).join(', ')}
      </div>
      <div className="autonomy-front">
        <strong>Esperando tu decisión:</strong>{' '}
        <Link to="/missions">{view.waiting.orchestratorMissions} misiones del orquestador</Link> ·{' '}
        <Link to="/dependencias">{view.waiting.pendingDependencies} dependencias pendientes</Link>
      </div>
    </div>
  )
}
```

`DashboardPage.tsx`: `import AutonomyPanel from '../components/AutonomyPanel'` y `<AutonomyPanel />` inmediatamente después de `<h1>Dashboard</h1>`.

`index.css` (al final):

```css
.autonomy {
  margin-bottom: 1.5rem;
}

.autonomy-switches {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem;
  margin-bottom: 0.5rem;
}

.autonomy-front {
  margin: 0.4rem 0;
}

.autonomy-steps {
  margin: 0.3rem 0 0 1rem;
  font-size: 0.9em;
}
```

- [ ] **Step 4: Build y lint**

Run: `cd app/frontend && npm run build && npm run lint`
Expected: build OK; lint sin errores (warnings preexistentes aceptados, ninguno nuevo en `AutonomyPanel.tsx`).

- [ ] **Step 5: Commit**

```bash
git add app/frontend/src
git commit -m "Command Center: modo automático y qué están haciendo arriba del Dashboard"
```

---

### Task 5: Documentación, despliegue y verificación en vivo

**Files:**
- Modify: `CLAUDE.md` (sección "Command Center web": Dashboard con modo automático; sección del orquestador: el interruptor del Dashboard y los comandos del chat)
- Modify: `docs/HISTORY.md` (entrada nueva al final)

- [ ] **Step 1: `CLAUDE.md`**

En "Command Center web", reemplazar `Interfaz principal para operar la compañía (regla del fundador: todo lo configurable se edita acá): Dashboard,` por `Interfaz principal para operar la compañía (regla del fundador: todo lo configurable se edita acá): Dashboard (arriba, **modo automático**: interruptores "Todo" / "Crear productos y servicios" = policy `ORCHESTRATOR_ENABLED` / "Buscar clientes" próximamente, y "qué están haciendo"; `AutonomyService`, `GET|PUT /api/company/autonomy`),`.

En el párrafo del orquestador, después de `"pausa/reanuda el orquestador" (gobernanza; cambia la policy).` agregar: ` Modo automático (spec 2026-09-29): "¿qué está en automático?" y los comandos "pon todo en automático" / "apaga el automático" (gobernanza) usan `AutonomyService`, igual que el Dashboard; encender lo ya encendido no crea versión.`

- [ ] **Step 2: `HISTORY.md`**

```markdown

### Modo automático (entrega A)

**Decisiones del fundador** (2026-09-29): "debe existir un botón donde yo ponga todo en automático… y un dashboard donde vea qué están haciendo". Un interruptor por frente más uno general; arriba del Dashboard; dos entregas (A: interruptores + panel; B: búsqueda de prospectos, que se engancha a "Buscar clientes"). Los interruptores son la policy versionada (una sola fuente de verdad con Settings y el chat).
```

- [ ] **Step 3: Suite, build y commit**

Run: `cd app && mvn test 2>&1 | grep -E "Tests run:.*Fail|BUILD" | tail -2 && cd frontend && npm run build`
Expected: `BUILD SUCCESS`; build OK.

```bash
git add CLAUDE.md docs/HISTORY.md
git commit -m "Documentar el modo automático"
```

- [ ] **Step 4: Desplegar**

Confirmar que no hay misiones en curso (`curl -s localhost:8081/api/company/missions` sin estados distintos de `AWAITING_INVESTOR|COMPLETED|FAILED|CANCELLED`); luego `docker compose build company-core && docker compose up -d company-core` y esperar `/actuator/health` `UP`.

- [ ] **Step 5: Verificar en vivo (sin encender el orquestador salvo que el fundador lo pida)**

- `curl -s localhost:8081/api/company/autonomy` → `products.enabled=false` con el `pauseReason` del corte por fallos; `clients.available=false`; conteos coherentes con Missions/Dependencias.
- `curl -s -X PUT localhost:8081/api/company/autonomy -H 'Content-Type: application/json' -d '{"clients":true}'` → 500 con "próximamente"; la policy no cambió.
- `curl -s -X PUT ... -d '{}'` → 200 con la misma vista; sin versión nueva en `GET /api/company/policies`.
- Dashboard en el navegador: la tarjeta muestra el interruptor apagado con el motivo en rojo, "Buscar clientes (próximamente)", agentes trabajando y "esperando tu decisión".
- Encender desde el Dashboard solo si el fundador lo autoriza (lanza un ciclo con costo de modelos).

Registrar lo verificado en `docs/HISTORY.md` (commit "HISTORY: verificación en vivo del modo automático").

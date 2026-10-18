# Misiones durables y corrección automática — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que una misión del Development Group sobreviva un reinicio de `company-core` retomándose donde quedó, que las demás queden `INTERRUPTED` (nunca `FAILED`), y que una misión que falla se corrija sola contra sus HU, criterios y escenarios hasta `VERIFIED` o `BLOCKED`.

**Architecture:** Enfoque A del spec: nada de etapas nuevas; Java deduce desde dónde seguir con lo que ya se guarda (estado, ronda, incremento, requisitos, plan de Neo, tareas con commit, git). Un paquete nuevo `com.aicompany.core.recovery` con dos funciones puras (`ResumePoint`, `CorrectionReport`), un servicio de memoria (`RecoveryMemoryService`) y el despachador (`MissionRecoveryService`); `MissionExecutor` gana un registro de misiones vivas y los puntos de entrada `resumeAsync` / `correctAsync` / `interrupt` / `block`; `DevelopmentTeamStrategy` salta lo ya commiteado y limpia el workspace.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`), Neo4j driver plano (Cypher a mano), JUnit 5 + Mockito, git real en `@TempDir` para el workspace, React + TS en `app/frontend`.

**Spec:** `docs/superpowers/specs/2026-10-05-misiones-durables-design.md`

## Global Constraints

- Estados nuevos: `MissionStatus.INTERRUPTED` y `MissionStatus.BLOCKED`. Un reinicio nunca deja una misión en `FAILED`.
- Policies nuevas: `MAX_AUTO_CORRECTIONS` (default 3, por incremento) y `MAX_MISSION_COST_USD` (default 5); ambas > 0 (validación de siempre).
- Freno fijo "sin avance": 2 rondas de corrección seguidas sin bajar el conteo de cosas que fallan → `BLOCKED`.
- Una ronda de corrección es `evidenceRound + 1` (ids `-R<n>`, CAS `claimNextRound`) y **no consume** `MAX_EVIDENCE_ROUNDS`.
- Retomar nunca gasta otra ronda ni otro número de ronda.
- Una misión de desarrollo a la vez desde la recuperación; las misiones que lanza el fundador no se encolan.
- No entran en corrección: `UNVALIDATED`, `CANCELLED`, sin requisitos aprobados, `AWAITING_SPEC_APPROVAL`, `COMPLETED`, `BLOCKED`.
- Eventos nuevos (prefijo obligatorio `EMPRESA_`): `EMPRESA_MISSION_RESUMED`, `EMPRESA_MISSION_INTERRUPTED`, `EMPRESA_MISSION_CORRECTION_STARTED`, `EMPRESA_MISSION_BLOCKED`.
- Convenciones del repo: Jackson 3, sin `@Async` (executors explícitos), `IllegalArgumentException`/`IllegalStateException` → 500, determinismo en Java (ningún modelo decide la recuperación ni los frenos), `MissionExecutor.advanceMission` es el único que persiste transiciones.
- Los `*MemoryService` no llevan test unitario: su Cypher nueva se verifica con `EXPLAIN` contra el Neo4j real (solo lectura) y se cubre vía los tests de quien los usa.
- Comandos: `cd app && mvn test` (suite), `mvn test -Dtest=Clase#metodo`; `cd app/frontend && npm run build && npm run lint`.

## Review Focus

1. **Reinicio entre el commit y el registro en Neo4j**: el último commit lleva el trailer de una tarea que quedó `RUNNING`; debe retrocederse y la tarea repetirse, sin perder los commits de Forjai (Task 3 y Task 5 lo prueban).
2. **Despachos simultáneos** (arranque + fin de misión + chequeo programado al mismo tiempo): una misión no puede lanzarse dos veces (Task 4 prueba el registro; Task 9 que el despachador, `synchronized`, no relanza una viva).
3. **Falla de infraestructura repetida** (sandbox o modelo caído en cada ronda): debe terminar en `BLOCKED` por "sin avance" o contador, nunca en bucle (Task 9).
4. **Reinicio durante una ronda de corrección**: se retoma la misma ronda con su pedido guardado, sin contar otra corrección (Task 6 y Task 8).
5. **Misión que el fundador lanza mientras la recuperación espera**: la recuperación no la toca ni la cuenta como huérfana (Task 4 y Task 9).

---

### Task 1: Estados, policies y su presencia en la UI

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/MissionStatus.java`
- Modify: `app/src/main/java/com/aicompany/core/model/PolicyKey.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyPolicyService.java:43-63`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java` (`DELETABLE_STATUSES`, ~línea 415)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (`advanceMission`, correo de `BLOCKED`)
- Modify: `app/frontend/src/api/types.ts`, `app/frontend/src/statusColor.ts`, `app/frontend/src/pages/SettingsPage.tsx`
- Test: `app/src/test/java/com/aicompany/core/service/CompanyPolicyServiceTest.java`, `MissionServiceTest.java`, `MissionExecutorTest.java`, `ProductOrchestratorTest.java`

**Interfaces:**
- Produces: `MissionStatus.INTERRUPTED`, `MissionStatus.BLOCKED`; `PolicyKey.MAX_AUTO_CORRECTIONS`, `PolicyKey.MAX_MISSION_COST_USD`.

- [ ] **Step 1: Tests que fallan**

En `CompanyPolicyServiceTest` (usar el patrón de los tests de defaults existentes):

```java
// Spec misiones durables §3: los frenos de la corrección automática son policies editables.
@Test
void theCorrectionBrakesHaveTheirDefaults() {
    var defaults = CompanyPolicyService.defaults(appProperties);
    assertEquals(3.0, defaults.get(PolicyKey.MAX_AUTO_CORRECTIONS));
    assertEquals(5.0, defaults.get(PolicyKey.MAX_MISSION_COST_USD));
    assertThrows(IllegalArgumentException.class, () -> CompanyPolicyService.validateValue(PolicyKey.MAX_AUTO_CORRECTIONS, 0));
}
```

En `MissionServiceTest` (mismo armado que los tests de `delete` existentes):

```java
// Spec misiones durables §4: INTERRUPTED y BLOCKED se pueden borrar.
@Test
void interruptedAndBlockedMissionsCanBeDeleted() {
    for (var status : List.of(MissionStatus.INTERRUPTED, MissionStatus.BLOCKED)) {
        when(memory.find("M-X")).thenReturn(Optional.of(new MissionResponse("M-X", status, "TEST", 0, "", "", Instant.now(), null)));
        assertTrue(service.delete("M-X"));
    }
}
```

En `MissionExecutorTest` (usar el executor y el mock `alertMailService` del test):

```java
// Spec misiones durables §3: BLOCKED avisa por correo; INTERRUPTED no (no es una falla).
@Test
void blockedSendsMailAndInterruptedDoesNot() {
    executor.block("M-1", "Se agotaron las 3 correcciones automáticas.");
    verify(alertMailService).send(contains("M-1"), contains("Se agotaron"), eq(true));
    executor.interrupt("M-2");
    verify(alertMailService, never()).send(contains("M-2"), anyString(), anyBoolean());
}
```

(`block`/`interrupt` se implementan en este task; ver Step 3.)

En `ProductOrchestratorTest`:

```java
// Spec misiones durables §3: BLOCKED e INTERRUPTED esperan al fundador; el ciclo no avanza ni falla.
@Test
void aBlockedOrInterruptedBuildIsWaitingForTheFounder() {
    var p = product("P1", CatalogStatus.IN_CONSTRUCTION, "product", "SOFTWARE", 1);
    for (var status : List.of(MissionStatus.BLOCKED, MissionStatus.INTERRUPTED)) {
        building(p, status);
        when(products.view("P1")).thenReturn(Optional.of(view(p, List.of())));
        orchestrator.tick();
    }
    verify(automation, never()).buildFinished(anyString());
    assertTrue(saved.isEmpty());
}
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest='CompanyPolicyServiceTest,MissionServiceTest,MissionExecutorTest,ProductOrchestratorTest'`
Expected: error de compilación (`INTERRUPTED`, `BLOCKED`, `MAX_AUTO_CORRECTIONS`, `block`, `interrupt` no existen).

- [ ] **Step 3: Implementar**

`MissionStatus`: agregar `INTERRUPTED,` y `BLOCKED,` antes de `COMPLETED`.

`PolicyKey`: agregar al final `MAX_AUTO_CORRECTIONS, MAX_MISSION_COST_USD`.

`CompanyPolicyService.defaults`:

```java
        // Spec misiones durables §3: frenos de la corrección automática (por incremento y por misión).
        map.put(PolicyKey.MAX_AUTO_CORRECTIONS, 3.0);
        map.put(PolicyKey.MAX_MISSION_COST_USD, 5.0);
```

`MissionService.DELETABLE_STATUSES`: agregar `MissionStatus.INTERRUPTED, MissionStatus.BLOCKED`.

`MissionExecutor` (junto a `safeFail`):

```java
    /** Spec misiones durables §1: una interrupción no es una falla (sin correo, el orquestador no la cuenta). */
    public void interrupt(String missionId) {
        advanceMission(missionId, MissionStatus.INTERRUPTED, 100, "Interrumpida",
                "Interrumpida por un reinicio de company-core el " + java.time.Instant.now()
                        + ". Reanúdala con \"reanuda " + missionId + "\".");
        events.publish("EMPRESA_MISSION_INTERRUPTED", missionId, null, "system", Map.of());
    }

    /** Spec misiones durables §3: un freno de la corrección automática deja la misión esperando al fundador. */
    public void block(String missionId, String reason) {
        advanceMission(missionId, MissionStatus.BLOCKED, 100, "Bloqueada", reason);
        events.publish("EMPRESA_MISSION_BLOCKED", missionId, null, "system", Map.of("reason", reason));
    }
```

En `advanceMission`, después del `else if (status == MissionStatus.FAILED)` de los correos:

```java
        } else if (status == MissionStatus.BLOCKED) {
            alertMailService.send("Misión " + missionId + " bloqueada: espera tu indicación",
                    "La misión " + missionId + " quedó BLOCKED: " + message + "\n\nDecide en Missions o en el chat "
                            + "(\"pide más evidencia sobre " + missionId + ": <tu indicación>\", aprobar o rechazar).", true);
```

Frontend:
- `types.ts`: `MissionStatus` agrega `| 'INTERRUPTED' | 'BLOCKED'`; `PolicyKey` agrega `| 'MAX_AUTO_CORRECTIONS' | 'MAX_MISSION_COST_USD'`.
- `statusColor.ts`: `'INTERRUPTED'` en `YELLOW`, `'BLOCKED'` en `RED`.
- `SettingsPage.tsx` (`POLICY_LABELS`, es `Record<PolicyKey, string>` y no compila sin ellas):

```ts
  MAX_AUTO_CORRECTIONS: 'Correcciones automáticas por incremento (máximo)',
  MAX_MISSION_COST_USD: 'Costo máximo de modelos por misión (US$)',
```

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn test -Dtest='CompanyPolicyServiceTest,MissionServiceTest,MissionExecutorTest,ProductOrchestratorTest'` → PASS.
Run: `cd app/frontend && npm run build && npm run lint` → build OK, sin advertencias nuevas (la de `SettingsPage.tsx` `set-state-in-effect` ya existía).

- [ ] **Step 5: Commit**

```bash
git add -A app && git commit -m "Durables: estados INTERRUPTED y BLOCKED, policies de los frenos"
```

---

### Task 2: Persistencia de la recuperación

**Files:**
- Create: `app/src/main/java/com/aicompany/core/recovery/CorrectionRound.java`
- Create: `app/src/main/java/com/aicompany/core/recovery/RecoveryMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java` (`createTask` ~línea 470; métodos nuevos)
- Modify: `app/src/main/java/com/aicompany/core/service/RequirementsMemoryService.java` (`claimDecision`, `pendingAdjustment`)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java` (`decideRequirements` pasa la instrucción; `evidenceRoundsUsed`)
- Test: `app/src/test/java/com/aicompany/core/service/MissionServiceRequirementsTest.java`

**Interfaces:**
- Produces:
  - `record CorrectionRound(int round, int increment, String request, int failing, boolean byFounder)`
  - `RecoveryMemoryService.addCorrectionRound(String missionId, CorrectionRound round)`, `List<CorrectionRound> correctionRounds(String missionId)`, `Optional<CorrectionRound> correctionRound(String missionId, int round)`
  - `MissionMemoryService.resetWorkingAgents() : int`, `Optional<String> planJson(String taskId)`, `Optional<Instant> createdAt(String missionId)`
  - `RequirementsMemoryService.claimDecision(String missionId, int version, String pendingAdjustment) : boolean`, `Optional<String> pendingAdjustment(String missionId)` (instrucción de un `ADJUST` reclamado sobre la última versión `DRAFT`)
  - `MissionService.evidenceRoundsUsed(missionId)` descuenta también las rondas de corrección.

- [ ] **Step 1: Tests que fallan**

En `MissionServiceRequirementsTest`:

```java
// Spec misiones durables §2: el ajuste reclamado guarda su instrucción, para retomarlo tras un reinicio.
@Test
void anAdjustmentClaimKeepsItsInstruction() {
    when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
    when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "DRAFT", "", null)));
    when(requirements.claimDecision(anyString(), anyInt(), any())).thenReturn(true);

    service.decideRequirements("M-9", new RequirementsDecisionCommand(
            RequirementsDecisionCommand.RequirementsDecision.ADJUST, " solo clientes "));

    verify(requirements).claimDecision("M-9", 1, "solo clientes");
}

// Spec misiones durables §3: las correcciones no consumen rondas de evidencia.
@Test
void correctionRoundsDoNotCountAgainstTheEvidenceLimit() {
    var recovery = mock(com.aicompany.core.recovery.RecoveryMemoryService.class);
    service.setRecoveryMemory(recovery);
    when(memory.evidenceRound("M-9")).thenReturn(4);
    when(requirements.incrementRounds("M-9")).thenReturn(1);
    when(recovery.correctionRounds("M-9")).thenReturn(List.of(
            new com.aicompany.core.recovery.CorrectionRound(2, 1, "x", 3, false),
            new com.aicompany.core.recovery.CorrectionRound(3, 1, "y", 2, false)));
    assertEquals(1, service.evidenceRoundsUsed("M-9"));
}
```

Actualizar el bloque de inicialización del test: `when(requirements.claimDecision(anyString(), anyInt(), any())).thenReturn(true);` (reemplaza el stub de 2 argumentos) y los `verify(requirements).claimDecision(...)`/stubs existentes a la firma de 3 argumentos.

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest=MissionServiceRequirementsTest` → error de compilación.

- [ ] **Step 3: Implementar**

`recovery/CorrectionRound.java`:

```java
package com.aicompany.core.recovery;

/**
 * Spec misiones durables §3: una ronda de corrección (automática o con la indicación del fundador sobre una BLOCKED).
 * {@code failing}: cuántas cosas fallaban al lanzarla (freno "sin avance"); {@code request}: el pedido completo, para
 * retomarla tras un reinicio.
 */
public record CorrectionRound(int round, int increment, String request, int failing, boolean byFounder) {
}
```

`recovery/RecoveryMemoryService.java` (las rondas se guardan como un JSON en `Mission.correctionRounds`):

```java
package com.aicompany.core.recovery;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Spec misiones durables §3: rondas de corrección de cada misión, en {@code Mission.correctionRounds} (JSON). */
@Service
public class RecoveryMemoryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Driver driver;

    public RecoveryMemoryService(Driver driver) {
        this.driver = driver;
    }

    public synchronized void addCorrectionRound(String missionId, CorrectionRound round) {
        var rounds = new ArrayList<>(correctionRounds(missionId));
        rounds.add(round);
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (m:Mission {id:$id}) SET m.correctionRounds = $json",
                    Map.of("id", missionId, "json", JSON.writeValueAsString(rounds))).consume());
        }
    }

    public List<CorrectionRound> correctionRounds(String missionId) {
        try (var session = driver.session()) {
            var json = session.run("MATCH (m:Mission {id:$id}) RETURN m.correctionRounds AS r", Map.of("id", missionId))
                    .list(r -> r.get("r").isNull() ? null : r.get("r").asString()).stream().findFirst().orElse(null);
            return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<CorrectionRound>>() { });
        }
    }

    public Optional<CorrectionRound> correctionRound(String missionId, int round) {
        return correctionRounds(missionId).stream().filter(r -> r.round() == round).findFirst();
    }
}
```

`MissionMemoryService`:
- `createTask` (Cypher): reemplazar `t.status='PENDING'` por `t.status = CASE WHEN t.status = 'COMPLETED' THEN t.status ELSE 'PENDING' END` (comentario: "Spec misiones durables §2: retomar no pisa una tarea ya terminada").
- Nuevos:

```java
    /** Spec misiones durables §1: al arrancar ningún agente está trabajando (sus hilos murieron con el proceso). */
    public int resetWorkingAgents() {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> tx.run("MATCH (a:Agent {status:'WORKING'}) SET a.status = 'IDLE' "
                    + "RETURN count(a) AS n").single().get("n").asInt());
        }
    }

    /** Spec misiones durables §2: el plan de Neo guardado como resultado de su tarea de planificación. */
    public Optional<String> planJson(String taskId) {
        try (var session = driver.session()) {
            return session.run("MATCH (t:AgentTask {id:$id, kind:'PLANNING', status:'COMPLETED'}) WHERE t.result IS NOT NULL "
                    + "RETURN t.result AS r", Map.of("id", taskId)).list(r -> r.get("r").asString()).stream().findFirst();
        }
    }

    /** Spec misiones durables §1: la más antigua se recupera primero. */
    public Optional<Instant> createdAt(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (m:Mission {id:$id}) WHERE m.createdAt IS NOT NULL RETURN m.createdAt AS c",
                    Map.of("id", missionId)).list(r -> Instant.parse(r.get("c").asString())).stream().findFirst();
        }
    }
```

(Verificar en `ensureMission` cómo se guarda `createdAt`; si es otro nombre o tipo, usar ese.)

`RequirementsMemoryService.claimDecision(missionId, version, pendingAdjustment)`: igual que hoy, más `SET r.pendingAdjustment = $adjust` (`null` si no es un ajuste). Nuevo:

```java
    /** Spec misiones durables §2: el ajuste reclamado sobre la última versión DRAFT (Aria no llegó a escribir la nueva). */
    public Optional<String> pendingAdjustment(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {status:'DRAFT'}) "
                            + "WHERE r.decisionClaimedAt IS NOT NULL AND r.pendingAdjustment IS NOT NULL "
                            + "RETURN r.pendingAdjustment AS a ORDER BY r.version DESC LIMIT 1", Map.of("id", missionId))
                    .list(r -> r.get("a").asString()).stream().findFirst();
        }
    }
```

`MissionService`:
- `decideRequirements`: `requirementsMemory.claimDecision(missionId, version.version(), command.decision() == ADJUST ? command.instruction().strip() : null)`.
- Setter opcional y la cuenta:

```java
    private com.aicompany.core.recovery.RecoveryMemoryService recoveryMemory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecoveryMemory(com.aicompany.core.recovery.RecoveryMemoryService recoveryMemory) {
        this.recoveryMemory = recoveryMemory;
    }

    public int evidenceRoundsUsed(String missionId) {
        return memory.evidenceRound(missionId)
                - (requirementsMemory == null ? 0 : requirementsMemory.incrementRounds(missionId))
                - (recoveryMemory == null ? 0 : recoveryMemory.correctionRounds(missionId).size());
    }
```

- [ ] **Step 4: Verificar la Cypher nueva contra Neo4j real (solo lectura)**

```bash
cd /home/alex/Documentos/projectos/empresa; PW=$(grep -E '^NEO4J_PASSWORD=' .env | cut -d= -f2-)
for q in "EXPLAIN MATCH (a:Agent {status:'WORKING'}) SET a.status = 'IDLE' RETURN count(a) AS n" \
 "EXPLAIN MATCH (m:Mission {id:'X'}) SET m.correctionRounds = '[]'" \
 "EXPLAIN MATCH (t:AgentTask {id:'X'}) SET t.status = CASE WHEN t.status = 'COMPLETED' THEN t.status ELSE 'PENDING' END" \
 "EXPLAIN MATCH (:Mission {id:'X'})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {status:'DRAFT'}) WHERE r.decisionClaimedAt IS NOT NULL AND r.pendingAdjustment IS NOT NULL RETURN r.pendingAdjustment AS a ORDER BY r.version DESC LIMIT 1"; do
  docker exec neo4j cypher-shell -u neo4j -p "$PW" "$q" 2>&1 | grep -E 'Plan|Error'; done
```

Expected: cuatro `Plan: "EXPLAIN"`, ningún `Error`. Nunca imprimir la clave.

- [ ] **Step 5: Correr los tests**

Run: `cd app && mvn test -Dtest='MissionServiceRequirementsTest,MissionServiceTest,ProductOrchestratorTest'` → PASS.

- [ ] **Step 6: Commit**

```bash
git add -A app && git commit -m "Durables: rondas de corrección, ajuste pendiente y tareas terminadas que no se pisan"
```

---

### Task 3: Workspace listo para retomar

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentWorkspaceService.java`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentWorkspaceServiceTest.java`

**Interfaces:**
- Produces:
  - `Optional<String> headIfAny(String missionId)`: `HEAD` o vacío si no hay workspace o no tiene commits.
  - `void discardUncommitted(String missionId)`: `reset --hard HEAD` + `clean -fd`.
  - `Optional<String> headTask(String missionId)`: valor del trailer `Forjai-Task` del commit `HEAD`.
  - `void dropHeadCommit(String missionId)`: `reset --hard HEAD~1`.

- [ ] **Step 1: Tests que fallan** (git real, usando el `service()` y el `@TempDir` del test existente)

```java
// Spec misiones durables §2: el workspace se limpia antes de retomar.
@Test
void resumingDiscardsWhatWasNotCommittedAndReadsTheLastTask() throws Exception {
    var service = service();
    service.commitAgentWork("M-1", "M-1-BACKEND", "backend", "Iris",
            new DevelopmentResult("primero", List.of(new DevelopmentResult.GeneratedFile("src/A.cs", "class A {}"))));
    var dir = service.missionWorkspace("M-1");
    java.nio.file.Files.writeString(dir.resolve("src/A.cs"), "a medias");
    java.nio.file.Files.writeString(dir.resolve("src/B.cs"), "sin commit");

    service.discardUncommitted("M-1");

    assertEquals("class A {}", java.nio.file.Files.readString(dir.resolve("src/A.cs")));
    assertFalse(java.nio.file.Files.exists(dir.resolve("src/B.cs")));
    assertEquals(Optional.of("M-1-BACKEND"), service.headTask("M-1"));
}

@Test
void anUnrecordedLastCommitCanBeDropped() throws Exception {
    var service = service();
    var first = service.commitAgentWork("M-1", "M-1-SCAFFOLD", "forjai", "Forjai",
            new DevelopmentResult("scaffold", List.of(new DevelopmentResult.GeneratedFile("a.txt", "1"))));
    service.commitAgentWork("M-1", "M-1-BACKEND", "backend", "Iris",
            new DevelopmentResult("backend", List.of(new DevelopmentResult.GeneratedFile("b.txt", "2"))));

    service.dropHeadCommit("M-1");

    assertEquals(Optional.of(first.sha()), service.headIfAny("M-1"));
}

@Test
void aMissionWithoutWorkspaceHasNoHead() {
    assertEquals(Optional.empty(), service().headIfAny("M-NUEVA"));
}
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest=DevelopmentWorkspaceServiceTest` → error de compilación.

- [ ] **Step 3: Implementar** (en `DevelopmentWorkspaceService`, `synchronized` como `commitAgentWork`)

```java
    /** Spec misiones durables §2: HEAD si la misión ya tiene repositorio con commits; vacío si no. */
    public Optional<String> headIfAny(String missionId) {
        var dir = missionWorkspace(missionId);
        if (!Files.exists(dir.resolve(".git"))) {
            return Optional.empty();
        }
        try {
            return Optional.of(git.run(dir, "rev-parse", "HEAD").trim());
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /** Spec misiones durables §2: lo que quedó escrito sin commit (el proceso murió a mitad) se descarta. */
    public synchronized void discardUncommitted(String missionId) throws IOException {
        var dir = missionWorkspace(missionId);
        git.run(dir, "reset", "-q", "--hard", "HEAD");
        git.run(dir, "clean", "-q", "-fd");
    }

    /** Spec misiones durables §2: la tarea del último commit (trailer Forjai-Task). */
    public Optional<String> headTask(String missionId) throws IOException {
        var body = git.run(missionWorkspace(missionId), "log", "-1", "--format=%B");
        return lines(body).stream().filter(l -> l.startsWith("Forjai-Task:"))
                .map(l -> l.substring("Forjai-Task:".length()).strip()).findFirst();
    }

    /** Spec misiones durables §2: el último commit no llegó a registrarse en Neo4j; su tarea se repite. */
    public synchronized void dropHeadCommit(String missionId) throws IOException {
        git.run(missionWorkspace(missionId), "reset", "-q", "--hard", "HEAD~1");
    }
```

(Agregar `import java.util.Optional;` si falta.)

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn test -Dtest=DevelopmentWorkspaceServiceTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app && git commit -m "Durables: el workspace se limpia antes de retomar"
```

---

### Task 4: Registro de misiones vivas en `MissionExecutor`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (`executeAsync` ~144, `reexecuteAsync` ~195, `submit` ~520)
- Test: `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java`

**Interfaces:**
- Produces: `boolean isLive(String missionId)`, `Set<String> liveMissionIds()`. (Los despachos simultáneos se evitan en `MissionRecoveryService`, que es `synchronized` y consulta `isLive` antes de lanzar.)

- [ ] **Step 1: Test que falla** (el test arma el executor con un `Executor`; usar uno que retenga la tarea para observar el estado intermedio)

```java
// Spec misiones durables §2 y §5: una misión con hilo vivo está registrada; al terminar sale; no se lanza dos veces.
@Test
void aRunningMissionIsLiveUntilItsThreadEnds() throws Exception {
    var pending = new java.util.ArrayList<Runnable>();
    var held = executorWith(pending::add);   // helper del test: mismo constructor que `executor`, con este Executor
    held.executeAsync("M-1", "x");
    assertTrue(held.isLive("M-1"));
    assertEquals(java.util.Set.of("M-1"), held.liveMissionIds());
    pending.forEach(Runnable::run);
    assertFalse(held.isLive("M-1"));
}
```

Si el test no tiene `executorWith`, crearlo copiando la construcción del `executor` del test y cambiando solo el `Executor`.

- [ ] **Step 2: Correr y ver que falla**

Run: `cd app && mvn test -Dtest=MissionExecutorTest#aRunningMissionIsLiveUntilItsThreadEnds` → error de compilación.

- [ ] **Step 3: Implementar**

```java
    /** Spec misiones durables §2: misiones con un hilo trabajando. Al arrancar está vacío: toda misión en curso es huérfana. */
    private final java.util.Set<String> liveMissions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean isLive(String missionId) {
        return liveMissions.contains(missionId);
    }

    public java.util.Set<String> liveMissionIds() {
        return java.util.Set.copyOf(liveMissions);
    }

    /** Corre {@code work} en el pool de misiones con la misión registrada como viva mientras dure. */
    private CompletableFuture<Void> runLive(String missionId, Runnable work) {
        liveMissions.add(missionId);
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    ModelCallContext.with(missionId, null, work);
                } finally {
                    liveMissions.remove(missionId);
                }
            }, orchestratorExecutor);
        } catch (RuntimeException ex) {
            liveMissions.remove(missionId);
            throw ex;
        }
    }
```

Usar `runLive` en los tres lugares que hoy llaman a `CompletableFuture.runAsync(... orchestratorExecutor)`: `executeAsync` (con `executeInternal`), `reexecuteAsync` (con `reexecuteInternal`) y `submit` (el cuerpo con su `try/catch` + `safeFail` queda dentro del `Runnable`). El manejo de errores de cada uno no cambia.

- [ ] **Step 4: Correr la suite del executor**

Run: `cd app && mvn test -Dtest='MissionExecutorTest,MissionExecutorTeamTest'` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app && git commit -m "Durables: registro de misiones vivas"
```

---

### Task 5: `DevelopmentTeamStrategy` retoma sin repetir trabajo

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (`execute` ~103-205)
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: Task 3 (`headIfAny`, `discardUncommitted`, `headTask`, `dropHeadCommit`); Task 2 (`createTask` no pisa `COMPLETED`).
- Produces: comportamiento — en una misión con workspace, las tareas `WORK` de la ronda `COMPLETED` con `commitSha` no se regeneran.

- [ ] **Step 1: Tests que fallan** (usar los helpers de plan/contexto que ya usan los tests de generación del archivo)

```java
// Spec misiones durables §2: retomar salta las tareas ya commiteadas (con sus paquetes) y limpia el workspace.
@Test
void resumingSkipsCommittedTasksAndCleansTheWorkspace() throws Exception {
    var context = /* contexto de 2 tareas WORK (backend, frontend-ui) + VALIDATION, ronda 0, como en los tests existentes */;
    when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
    when(workspace.headTask("M-1")).thenReturn(Optional.of("M-1-BACKEND"));
    when(memory.tasks("M-1")).thenReturn(List.of(new AgentTask("M-1-BACKEND", "M-1", "backend", "API", "COMPLETED",
            "{\"summary\":\"api\",\"files\":[],\"packages\":[{\"name\":\"Npgsql\",\"version\":\"8.0.5\"}]}",
            Instant.now(), "WORK", "/w", "a".repeat(40), List.of("src/Api/X.cs"), null, null)));

    strategy.execute(context, progress);

    verify(workspace).discardUncommitted("M-1");
    verify(workspace, never()).dropHeadCommit("M-1");
    verify(runtime, never()).generate(eq("M-1-BACKEND"), any(), any(), any(), any(), any());
    verify(runtime, never()).generate(eq("M-1-BACKEND"), any(), any(), any(), any(), any(), any());
    verify(runtime, never()).generate(eq("M-1-BACKEND"), any(), any(), any(), any(), any(), any(), any());
}

// Review Focus 1: el último commit es de una tarea que no quedó COMPLETED → se retrocede y la tarea se repite.
@Test
void anUnrecordedLastCommitIsDroppedAndItsTaskRepeated() throws Exception {
    var context = /* mismo contexto */;
    when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
    when(workspace.headTask("M-1")).thenReturn(Optional.of("M-1-BACKEND"));
    when(memory.tasks("M-1")).thenReturn(List.of(new AgentTask("M-1-BACKEND", "M-1", "backend", "API", "RUNNING",
            null, Instant.now(), "WORK", null, null, null, null, null)));

    strategy.execute(context, progress);

    verify(workspace).dropHeadCommit("M-1");
}

// Ronda 0 con workspace: el scaffold existente se reutiliza (no se vuelve a escribir encima del código).
@Test
void aRound0WithWorkspaceReusesTheScaffold() throws Exception {
    var context = /* mismo contexto */;
    when(workspace.headIfAny("M-1")).thenReturn(Optional.of("a".repeat(40)));
    when(workspace.headSha("M-1")).thenReturn("a".repeat(40));
    strategy.execute(context, progress);
    verify(workspace, never()).commitAgentWork(eq("M-1"), eq("M-1-SCAFFOLD"), any(), any(), any());
}
```

Completar los `/* contexto */` con el mismo armado (`TeamMissionContext`, `progress`, stubs de `runtime.generate`, sandbox) que usa el test de generación por capas más cercano del archivo; las tres pruebas comparten un helper `resumableContext()` creado en este step.

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest=DevelopmentTeamStrategyTest` → los 3 tests nuevos fallan.

- [ ] **Step 3: Implementar** (al principio de `execute`, antes del bucle de `createTask`)

```java
        // Spec misiones durables §2: con workspace, primero se limpia y se lee lo ya commiteado de esta ronda.
        var head = workspace.headIfAny(missionId);
        var done = new LinkedHashMap<String, AgentTask>();
        if (head.isPresent()) {
            var byId = memory.tasks(missionId).stream()
                    .collect(Collectors.toMap(AgentTask::taskId, t -> t, (a, b) -> b));
            try {
                workspace.discardUncommitted(missionId);
                var headTask = workspace.headTask(missionId).orElse(null);
                var headRecord = headTask == null ? null : byId.get(headTask);
                if (headRecord != null && TeamPlan.KIND_WORK.equals(headRecord.kind())
                        && !"COMPLETED".equals(headRecord.status())) {
                    workspace.dropHeadCommit(missionId);   // Review Focus 1
                }
            } catch (Exception ex) {
                throw new IllegalStateException("No se pudo preparar el repositorio para retomar: "
                        + safeMessage(ex, "error de Git"), ex);
            }
            for (var task : work) {
                var record = byId.get(taskId(context, task.agentId()));
                if (record != null && "COMPLETED".equals(record.status()) && record.commitSha() != null) {
                    done.put(record.taskId(), record);
                }
            }
        }
```

- Scaffold: `scaffold = context.round() > 0 || head.isPresent() ? existingScaffold(context) : commitProjectScaffold(context);`
- En el bucle de generación, antes de `progress.advance(...)`:

```java
            var already = done.get(id);
            if (already != null) {
                // Spec misiones durables §2: ya commiteada antes del reinicio; sus paquetes salen del resultado guardado.
                committed.add(new CommittedWork(id, task.agentId(), already.commitSha(),
                        already.files() == null ? List.of() : already.files()));
                generationHead = already.commitSha();
                try {
                    var saved = jsonMapper.readValue(already.result(), DevelopmentResult.class);
                    if (!saved.packagesOrEmpty().isEmpty()) {
                        packagesByTask.put(task, saved.packagesOrEmpty());
                    }
                } catch (Exception ex) {
                    log.warn("MISSION {} - could not read the saved result of {}: {}", missionId, id, ex.getMessage());
                }
                continue;
            }
```

(El campo `jsonMapper` ya existe en la clase; si se llama distinto, usar ese. `createTask` sigue corriendo para todas: por Task 2 no pisa las `COMPLETED`.)

- [ ] **Step 4: Correr la suite de la estrategia**

Run: `cd app && mvn test -Dtest=DevelopmentTeamStrategyTest` → PASS (los 63 existentes siguen verdes: sin workspace, `headIfAny` vacío → flujo de siempre; el mock de `workspace` devuelve `Optional.empty()` por defecto solo si se stubbea: agregar en el setup del test `when(workspace.headIfAny(anyString())).thenReturn(Optional.empty());`).

- [ ] **Step 5: Commit**

```bash
git add -A app && git commit -m "Durables: la estrategia de desarrollo retoma sin repetir lo commiteado"
```

---

### Task 6: `ResumePoint` (función pura)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/recovery/MissionSnapshot.java`
- Create: `app/src/main/java/com/aicompany/core/recovery/ResumePoint.java`
- Test: `app/src/test/java/com/aicompany/core/recovery/ResumePointTest.java`

**Interfaces:**
- Produces:

```java
public record MissionSnapshot(String missionId, String teamId, int round, Integer incrementOfRound, boolean correctionRound,
                              String latestRequirementsStatus, boolean requirementsApproved, String pendingAdjustment,
                              boolean planForRound, List<RoundTask> workTasks) {
    public record RoundTask(String taskId, String status, String commitSha) { }
}

public record ResumePoint(Stage stage, int round, RoundKind kind, Integer increment) {
    public enum Stage { REQUIREMENTS, ADJUSTMENT, AWAIT_APPROVAL, PLAN, GENERATE, VERIFY, INTERRUPT }
    public enum RoundKind { ORIGINAL, INCREMENT, EVIDENCE, CORRECTION }
    public static ResumePoint of(MissionSnapshot s) { ... }
}
```

- [ ] **Step 1: Tests que fallan**

```java
package com.aicompany.core.recovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec misiones durables §2: desde dónde se retoma una huérfana, decidido en Java. */
class ResumePointTest {

    private static MissionSnapshot dev(int round, Integer increment, boolean correction, String reqStatus, boolean approved,
                                       String adjustment, boolean plan, List<MissionSnapshot.RoundTask> tasks) {
        return new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", round, increment, correction, reqStatus, approved, adjustment,
                plan, tasks);
    }

    @Test void withoutTeamOrOutsideTheDevelopmentGroupItIsInterrupted() {
        assertEquals(ResumePoint.Stage.INTERRUPT, ResumePoint.of(new MissionSnapshot("M-1", null, 0, null, false, null, false,
                null, false, List.of())).stage());
        assertEquals(ResumePoint.Stage.INTERRUPT, ResumePoint.of(new MissionSnapshot("M-1", "TEAM-MARKETING-GROWTH", 0, null,
                false, null, false, null, false, List.of())).stage());
    }
    @Test void withoutRequirementsAriaWritesAgain() {
        assertEquals(ResumePoint.Stage.REQUIREMENTS, ResumePoint.of(dev(0, null, false, null, false, null, false, List.of())).stage());
    }
    @Test void aClaimedAdjustmentIsRepeated() {
        assertEquals(ResumePoint.Stage.ADJUSTMENT, ResumePoint.of(dev(0, null, false, "DRAFT", false, "solo clientes", false,
                List.of())).stage());
    }
    @Test void aDraftNotYetAwaitingGoesToApproval() {
        assertEquals(ResumePoint.Stage.AWAIT_APPROVAL, ResumePoint.of(dev(0, null, false, "DRAFT", false, null, false,
                List.of())).stage());
    }
    @Test void approvedWithoutPlanPlans() {
        var p = ResumePoint.of(dev(0, null, false, "APPROVED", true, null, false, List.of()));
        assertEquals(ResumePoint.Stage.PLAN, p.stage());
        assertEquals(ResumePoint.RoundKind.ORIGINAL, p.kind());
    }
    @Test void aPlanWithATaskWithoutCommitGenerates() {
        var p = ResumePoint.of(dev(2, 2, false, "APPROVED", true, null, true, List.of(
                new MissionSnapshot.RoundTask("M-1-BACKEND-R2", "COMPLETED", "abc"),
                new MissionSnapshot.RoundTask("M-1-FRONTEND-UI-R2", "RUNNING", null))));
        assertEquals(ResumePoint.Stage.GENERATE, p.stage());
        assertEquals(ResumePoint.RoundKind.INCREMENT, p.kind());
        assertEquals(2, p.increment());
        assertEquals(2, p.round());
    }
    @Test void allTasksCommittedVerifies() {
        assertEquals(ResumePoint.Stage.VERIFY, ResumePoint.of(dev(0, null, false, "APPROVED", true, null, true, List.of(
                new MissionSnapshot.RoundTask("M-1-BACKEND", "COMPLETED", "abc")))).stage());
    }
    // Review Focus 4: una ronda de corrección cortada se retoma como corrección (misma ronda, sin contar otra).
    @Test void aCorrectionRoundIsResumedAsCorrection() {
        var p = ResumePoint.of(dev(3, null, true, "APPROVED", true, null, true, List.of()));
        assertEquals(ResumePoint.RoundKind.CORRECTION, p.kind());
        assertEquals(3, p.round());
    }
    @Test void anEvidenceRoundIsResumedAsEvidence() {
        assertEquals(ResumePoint.RoundKind.EVIDENCE, ResumePoint.of(dev(1, null, false, "APPROVED", true, null, true,
                List.of())).kind());
    }
}
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest=ResumePointTest` → error de compilación.

- [ ] **Step 3: Implementar**

`MissionSnapshot.java`: el record de **Interfaces** con el javadoc "Spec misiones durables §2: lo guardado de una misión huérfana, armado por `MissionRecoveryService`. `workTasks`: las tareas WORK de la ronda en curso; `incrementOfRound`: el incremento si la ronda es de incremento (`incrementRoundsByNumber`), o `buildingIncrement` en la ronda 0; `correctionRound`: la ronda está en `correctionRounds`."

`ResumePoint.java`:

```java
package com.aicompany.core.recovery;

/** Spec misiones durables §2: la etapa desde la que se retoma una huérfana (Java, sin modelo). */
public record ResumePoint(Stage stage, int round, RoundKind kind, Integer increment) {

    public enum Stage { REQUIREMENTS, ADJUSTMENT, AWAIT_APPROVAL, PLAN, GENERATE, VERIFY, INTERRUPT }

    public enum RoundKind { ORIGINAL, INCREMENT, EVIDENCE, CORRECTION }

    static final String DEVELOPMENT_TEAM = "TEAM-DEVELOPMENT";

    public static ResumePoint of(MissionSnapshot s) {
        var kind = s.round() == 0 ? RoundKind.ORIGINAL
                : s.correctionRound() ? RoundKind.CORRECTION
                : s.incrementOfRound() != null ? RoundKind.INCREMENT
                : RoundKind.EVIDENCE;
        var increment = s.incrementOfRound();
        if (!DEVELOPMENT_TEAM.equals(s.teamId())) {
            return new ResumePoint(Stage.INTERRUPT, s.round(), kind, increment);
        }
        if (!s.requirementsApproved()) {
            if (s.pendingAdjustment() != null) {
                return new ResumePoint(Stage.ADJUSTMENT, s.round(), kind, increment);
            }
            if ("DRAFT".equals(s.latestRequirementsStatus())) {
                return new ResumePoint(Stage.AWAIT_APPROVAL, s.round(), kind, increment);
            }
            return new ResumePoint(Stage.REQUIREMENTS, s.round(), kind, increment);
        }
        if (!s.planForRound()) {
            return new ResumePoint(Stage.PLAN, s.round(), kind, increment);
        }
        var allCommitted = !s.workTasks().isEmpty() && s.workTasks().stream()
                .allMatch(t -> "COMPLETED".equals(t.status()) && t.commitSha() != null);
        return new ResumePoint(allCommitted ? Stage.VERIFY : Stage.GENERATE, s.round(), kind, increment);
    }
}
```

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn test -Dtest=ResumePointTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app && git commit -m "Durables: ResumePoint decide desde dónde retomar"
```

---

### Task 7: `CorrectionReport` (función pura)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/recovery/CorrectionReport.java`
- Test: `app/src/test/java/com/aicompany/core/recovery/CorrectionReportTest.java`

**Interfaces:**
- Consumes: `AgentTask` (tarea `VALIDATION`: `staticChecks` JSON de `StaticCheck[]`, `sandboxResult` JSON de `SandboxResult`, `result` con el reporte de cobertura), `CompilerErrorParser`, `FailingTestParser` (ya existen en `agent/validation`).
- Produces: `record CorrectionReport(String text, int failing)`; `static CorrectionReport from(Optional<AgentTask> lastValidation, String missionMessage)`; `static final String HEADER = "CORRECCIÓN AUTOMÁTICA (Java)"`; `static final String RULE`.

- [ ] **Step 1: Tests que fallan**

```java
package com.aicompany.core.recovery;

import com.aicompany.core.model.AgentTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Spec misiones durables §3: lo que falla, armado en Java desde la última validación. */
class CorrectionReportTest {

    private static AgentTask validation(String checks, String sandbox, String result) {
        return new AgentTask("M-1-QA-REVIEW", "M-1", "qa", "CODE_REVIEW", "COMPLETED", result, Instant.now(), "VALIDATION",
                null, null, List.of(), "FAILED", checks, sandbox);
    }

    @Test void listsUncoveredScenariosFailedChecksAndTheFailedStep() {
        var report = CorrectionReport.from(Optional.of(validation(
                "[{\"check\":\"SCENARIO_COVERAGE\",\"status\":\"FAIL\",\"detail\":\"4/5: sin test aprobado ESC-05 (ver detalle)\"},"
                        + "{\"check\":\"DDD_LAYERS\",\"status\":\"FAIL\",\"detail\":\"domain importa infrastructure\"},"
                        + "{\"check\":\"ENTRY_FILES\",\"status\":\"PASS\",\"detail\":\"ok\"}]",
                "{\"overall\":\"FAIL\",\"steps\":[{\"name\":\"backend/test\",\"status\":\"FAIL\",\"exitCode\":1,\"durationMs\":1,"
                        + "\"outputTail\":\"Failed ESC_05_detalle\",\"testsPassed\":4,\"testsFailed\":1}]}",
                "Reporte de cobertura de escenarios (Java, QA casos de uso):\n❌ ESC-05 (incremento 1, nuevo) ver detalle: sin test aprobado")),
                "");

        assertTrue(report.text().startsWith(CorrectionReport.HEADER), report.text());
        assertTrue(report.text().contains("ESC-05") && report.text().contains("DDD_LAYERS")
                && report.text().contains("backend/test") && report.text().contains("Failed ESC_05_detalle"), report.text());
        assertFalse(report.text().contains("ENTRY_FILES"), report.text());
        assertTrue(report.text().contains(CorrectionReport.RULE));
        assertEquals(3, report.failing()); // 1 escenario + 1 chequeo no-cobertura + 1 paso
    }

    @Test void aFailureBeforeAnyCodeUsesTheMissionMessage() {
        var report = CorrectionReport.from(Optional.empty(), "El líder engineering no produjo un plan válido");
        assertTrue(report.text().contains("no produjo un plan válido"), report.text());
        assertEquals(1, report.failing());
    }
}
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest=CorrectionReportTest` → error de compilación.

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.recovery;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.SandboxResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Spec misiones durables §3: lo que falla de la última validación, para la ronda de corrección. {@code failing} = escenarios
 * sin test aprobado + chequeos fallidos (sin contar SCENARIO_COVERAGE) + pasos del sandbox fallidos; sin validación, 1
 * (la falla antes del código).
 */
public record CorrectionReport(String text, int failing) {

    public static final String HEADER = "CORRECCIÓN AUTOMÁTICA (Java)";
    public static final String RULE = "Corrige hasta que todos los escenarios del incremento pasen. No borres, renombres ni "
            + "debilites tests.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COVERAGE_REPORT = "Reporte de cobertura de escenarios (Java, QA casos de uso):";

    public static CorrectionReport from(Optional<AgentTask> lastValidation, String missionMessage) {
        var lines = new ArrayList<String>();
        var failing = 0;
        if (lastValidation.isEmpty()) {
            lines.add("- La misión falló antes de tener código: " + (missionMessage == null ? "" : missionMessage));
            failing = 1;
        } else {
            var task = lastValidation.get();
            var result = task.result() == null ? "" : task.result();
            if (result.startsWith(COVERAGE_REPORT)) {
                for (var line : result.substring(COVERAGE_REPORT.length()).split("\n")) {
                    if (line.strip().startsWith("❌")) {
                        lines.add("- Escenario sin test aprobado: " + line.strip().substring(1).strip());
                        failing++;
                    }
                }
            }
            try {
                if (task.staticChecks() != null) {
                    for (var node : JSON.readTree(task.staticChecks())) {
                        var check = node.path("check").asString();
                        if (!"PASS".equals(node.path("status").asString()) && !"SCENARIO_COVERAGE".equals(check)) {
                            lines.add("- Chequeo " + check + " falló: " + node.path("detail").asString());
                            failing++;
                        }
                    }
                }
                if (task.sandboxResult() != null) {
                    var sandbox = JSON.readValue(task.sandboxResult(), SandboxResult.class);
                    for (var step : sandbox.stepsOrEmpty()) {
                        if ("FAIL".equals(step.status()) || "TIMEOUT".equals(step.status())) {
                            var tail = step.outputTail() == null ? "" : step.outputTail();
                            lines.add("- Paso del sandbox " + step.name() + " " + step.status() + ":\n"
                                    + tail.substring(Math.max(0, tail.length() - 3000)));
                            failing++;
                        }
                    }
                }
            } catch (Exception ex) {
                lines.add("- (No se pudo leer el detalle de la validación: " + ex.getMessage() + ")");
            }
            if (failing == 0) {
                lines.add("- La validación terminó en " + task.validationStatus() + " sin un detalle legible.");
                failing = 1;
            }
        }
        return new CorrectionReport(HEADER + ":\n" + String.join("\n", lines) + "\n" + RULE, failing);
    }
}
```

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn test -Dtest=CorrectionReportTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app && git commit -m "Durables: CorrectionReport arma lo que falla desde la última validación"
```

---

### Task 8: Puntos de entrada en `MissionExecutor`: retomar y corregir

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java`
- Test: `app/src/test/java/com/aicompany/core/service/MissionExecutorTeamTest.java`

**Interfaces:**
- Consumes: `ResumePoint` (Task 6), `RecoveryMemoryService` (Task 2), `runLive` (Task 4), `interrupt` (Task 1).
- Produces:
  - `CompletableFuture<Void> resumeAsync(String missionId, ResumePoint point)`
  - `CompletableFuture<Void> correctAsync(String missionId, int round, String request)`
  - setter `setRecoveryMemory(RecoveryMemoryService)`.

- [ ] **Step 1: Tests que fallan** (fixtures de `MissionExecutorTeamTest`: `withAria()`, `planned(...)`, `planner`, `writer`, `requirements`, `development`)

```java
// Spec misiones durables §2: retomar la generación de la ronda 0 usa el plan guardado de esa ronda (sin replanificar).
@Test
void resumingTheOriginalRoundUsesItsSavedPlan() throws Exception {
    withAria();
    when(memory.planJson(TaskIds.planTask("M-9", "engineering", 0))).thenReturn(Optional.of(
            JsonMapper.builder().build().writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
    when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());

    executor.resumeAsync("M-9", new ResumePoint(ResumePoint.Stage.GENERATE, 0, ResumePoint.RoundKind.ORIGINAL, null)).get();

    verify(planner, never()).plan(any(), any(), any(), any());
    verify(development).execute(argThat(c -> c.round() == 0 && c.instruction().contains("INCREMENTO 1")), any());
    verify(events).publish(eq("EMPRESA_MISSION_RESUMED"), eq("M-9"), isNull(), eq("system"), anyMap());
}

// Spec misiones durables §2: sin plan, Neo planifica la misma ronda e incremento.
@Test
void resumingWithoutPlanPlansTheSameIncrement() throws Exception {
    withAria();
    when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any(), eq(2), any())).thenReturn(planned("TEAM-DEVELOPMENT"));
    executor.resumeAsync("M-9", new ResumePoint(ResumePoint.Stage.PLAN, 2, ResumePoint.RoundKind.INCREMENT, 2)).get();
    verify(planner).plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), contains("INCREMENTO 2"), any(), eq(2), any());
}

// Review Focus 4: una corrección cortada se retoma con su pedido guardado (misma ronda).
@Test
void resumingACorrectionRoundUsesItsSavedRequest() throws Exception {
    withAria();
    var recovery = mock(RecoveryMemoryService.class);
    executor.setRecoveryMemory(recovery);
    when(recovery.correctionRound("M-9", 3)).thenReturn(Optional.of(new CorrectionRound(3, 1, "CORRECCIÓN AUTOMÁTICA (Java):\n- x", 2, false)));
    when(memory.tasks("M-9")).thenReturn(List.of());
    when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());
    when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
            .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
    when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());

    executor.resumeAsync("M-9", new ResumePoint(ResumePoint.Stage.GENERATE, 3, ResumePoint.RoundKind.CORRECTION, null)).get();

    verify(development).execute(argThat(c -> c.round() == 3
            && c.plan().workTasks().stream().allMatch(t -> t.objective().contains("CORRECCIÓN AUTOMÁTICA"))), any());
    verify(recovery, never()).addCorrectionRound(any(), any());
}

// Spec misiones durables §1: discovery y otros equipos quedan INTERRUPTED.
@Test
void anInterruptPointLeavesTheMissionInterrupted() throws Exception {
    executor.resumeAsync("M-9", new ResumePoint(ResumePoint.Stage.INTERRUPT, 0, ResumePoint.RoundKind.ORIGINAL, null)).get();
    verify(memory).updateMission(eq("M-9"), eq(MissionStatus.INTERRUPTED), anyInt(), anyString(), anyString());
}

// Spec misiones durables §3: la corrección es una ronda de equipo con el reporte como pedido.
@Test
void aCorrectionRunsTheTeamRoundWithTheReport() throws Exception {
    withAria();
    when(memory.tasks("M-9")).thenReturn(List.of());
    when(ceoService.routeInvestorFeedback(any(), any(), any(), anyList(), any())).thenReturn(java.util.Map.of());
    when(memory.lastTeamPlanJson("M-9")).thenReturn(Optional.of(JsonMapper.builder().build()
            .writeValueAsString(planned("TEAM-DEVELOPMENT").plan())));
    when(planner.teamSnapshot("TEAM-DEVELOPMENT")).thenReturn(planned("TEAM-DEVELOPMENT").team());

    executor.correctAsync("M-9", 2, "CORRECCIÓN AUTOMÁTICA (Java):\n- ESC-05").get();

    verify(events).publish(eq("EMPRESA_MISSION_CORRECTION_STARTED"), eq("M-9"), isNull(), eq("system"), anyMap());
    verify(development).execute(argThat(c -> c.round() == 2), any());
}
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest=MissionExecutorTeamTest` → error de compilación.

- [ ] **Step 3: Implementar** (en `MissionExecutor`)

```java
    private com.aicompany.core.recovery.RecoveryMemoryService recoveryMemory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecoveryMemory(com.aicompany.core.recovery.RecoveryMemoryService recoveryMemory) {
        this.recoveryMemory = recoveryMemory;
    }

    /**
     * Spec misiones durables §2: retoma una huérfana desde {@code point}, en la misma ronda (nunca gasta otra). Discovery y
     * otros equipos quedan INTERRUPTED.
     */
    public CompletableFuture<Void> resumeAsync(String missionId, com.aicompany.core.recovery.ResumePoint point) {
        if (point.stage() == com.aicompany.core.recovery.ResumePoint.Stage.INTERRUPT) {
            interrupt(missionId);
            return CompletableFuture.completedFuture(null);
        }
        events.publish("EMPRESA_MISSION_RESUMED", missionId, null, "system",
                Map.of("stage", point.stage().name(), "round", point.round(), "kind", point.kind().name()));
        return submit(missionId, "resume " + point.stage(), () -> resumeInternal(missionId, point));
    }

    private void resumeInternal(String missionId, com.aicompany.core.recovery.ResumePoint point) {
        var teamId = memory.teamId(missionId).orElseThrow();
        var instruction = memory.instructionOf(missionId).orElse("");
        var mode = TeamExecutionMode.forTeamType(TeamMemoryService.teamType(teamId).orElseThrow());
        switch (point.stage()) {
            case REQUIREMENTS -> executeTeamMission(missionId, instruction, teamId);
            case ADJUSTMENT -> {
                var adjustment = requirementsMemory.pendingAdjustment(missionId).orElseThrow();
                var written = rewriteRequirements(missionId, instruction, adjustment);
                events.publish("EMPRESA_REQUIREMENTS_ADJUSTED", missionId, null, "human",
                        Map.of("version", written.version(), "instruction", adjustment));
                awaitApproval(missionId, written);
            }
            case AWAIT_APPROVAL -> {
                var latest = requirementsMemory.latest(missionId).orElseThrow();
                awaitApproval(missionId, new RequirementsWriter.Written(latest.version(), jsonMapper.readValue(
                        latest.specJson(), com.aicompany.core.agent.model.RequirementsSpec.class)));
            }
            case PLAN, GENERATE, VERIFY -> resumeRound(missionId, instruction, teamId, mode, point);
            case INTERRUPT -> interrupt(missionId);
        }
    }

    private void resumeRound(String missionId, String instruction, String teamId, TeamExecutionMode mode,
                             com.aicompany.core.recovery.ResumePoint point) {
        var round = point.round();
        switch (point.kind()) {
            case ORIGINAL, INCREMENT -> {
                var increment = point.increment() == null ? 1 : point.increment();
                var spec = approvedSpec(missionId);
                var team = teamWorkPlanner.teamSnapshot(teamId);
                var saved = memory.planJson(com.aicompany.core.model.TaskIds.planTask(missionId, team.leaderAgentId(), round));
                if (saved.isEmpty()) {
                    planAndRunIncrement(missionId, instruction, teamId, mode, spec, increment, round);
                    return;
                }
                var withIncrement = withIncrement(spec, increment, instruction);
                requirementsMemory.setBuildingIncrement(missionId, increment);
                advanceMission(missionId, MissionStatus.DELEGATING, 10, "Retomada",
                        "Retomando el incremento " + increment + " tras un reinicio.");
                runTeamPlan(missionId, withIncrement, mode, new TeamMissionContext(missionId, withIncrement, team,
                        jsonMapper.readValue(saved.get(), TeamPlan.class), round));
            }
            case EVIDENCE -> {
                var requests = memory.evidenceRequests(missionId);
                var request = requests.isEmpty() ? CeoService.NO_INVESTOR_COMMENT : requests.get(requests.size() - 1);
                reexecuteTeamMission(missionId, instruction, teamId, round, request, priorRoundResults(missionId, round),
                        companyMemory.agentModel("ceo", defaultCeoModel));
            }
            case CORRECTION -> {
                var request = recoveryMemory.correctionRound(missionId, round)
                        .map(com.aicompany.core.recovery.CorrectionRound::request).orElseThrow();
                reexecuteTeamMission(missionId, instruction, teamId, round, request, priorRoundResults(missionId, round),
                        companyMemory.agentModel("ceo", defaultCeoModel));
            }
        }
    }

    /**
     * Spec misiones durables §3: ronda de corrección ({@code round} ya reclamado con claimNextRound y registrado en
     * correctionRounds por MissionRecoveryService o MissionService). No consume MAX_EVIDENCE_ROUNDS.
     */
    public CompletableFuture<Void> correctAsync(String missionId, int round, String request) {
        events.publish("EMPRESA_MISSION_CORRECTION_STARTED", missionId, null, "system", Map.of("round", round));
        advanceMission(missionId, MissionStatus.DELEGATING, 10, "Corrección automática (ronda " + round + ")",
                "Corrigiendo contra las HU, los criterios y los escenarios del incremento.");
        return submit(missionId, "correction " + round, () -> reexecuteInternal(missionId,
                memory.instructionOf(missionId).orElse(""), round, request));
    }
```

(Si `executeTeamMission`, `rewriteRequirements`, `awaitApproval`, `approvedSpec`, `withIncrement`, `planAndRunIncrement`, `runTeamPlan`, `reexecuteTeamMission` y `priorRoundResults` tienen otra visibilidad o firma, ajustar la llamada; todos son privados de esta clase.)

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn test -Dtest='MissionExecutorTeamTest,MissionExecutorTest'` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app && git commit -m "Durables: MissionExecutor retoma desde un ResumePoint y lanza correcciones"
```

---

### Task 9: `MissionRecoveryService`: el despachador con sus frenos

**Files:**
- Create: `app/src/main/java/com/aicompany/core/recovery/MissionRecoveryService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (gancho al terminar)
- Modify: `app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java` (`@Order(0)` en el listener)
- Test: `app/src/test/java/com/aicompany/core/recovery/MissionRecoveryServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1-8.
- Produces:
  - `void recoverAll()` (arranque y chequeo), `void onMissionFinished(String missionId)`, `void resume(String missionId)` (a pedido, para `INTERRUPTED`), `List<String> resumedSinceStart()`.
  - `MissionSnapshot snapshot(MissionResponse mission)`.
  - `Optional<String> brake(String missionId, int increment, int failingNow)`: motivo del freno o vacío.

- [ ] **Step 1: Tests que fallan** (todo mockeado: `MissionMemoryService`, `RequirementsMemoryService`, `RecoveryMemoryService`, `MissionExecutor`, `CompanyPolicyService`, `ModelCostService`)

```java
package com.aicompany.core.recovery;

// imports: model.*, service.*, junit, mockito, java.time.Instant, java.util.*

/** Spec misiones durables §1 y §3: qué se recupera, en qué orden, una de desarrollo a la vez, y los frenos. */
class MissionRecoveryServiceTest {

    private final MissionMemoryService missions = mock(MissionMemoryService.class);
    private final RequirementsMemoryService requirements = mock(RequirementsMemoryService.class);
    private final RecoveryMemoryService recovery = mock(RecoveryMemoryService.class);
    private final MissionExecutor executor = mock(MissionExecutor.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final ModelCostService cost = mock(ModelCostService.class);
    private final MissionRecoveryService service = new MissionRecoveryService(missions, requirements, recovery, executor,
            policies, cost, Runnable::run);

    {
        when(policies.activeValue(PolicyKey.MAX_AUTO_CORRECTIONS)).thenReturn(3.0);
        when(policies.activeValue(PolicyKey.MAX_MISSION_COST_USD)).thenReturn(5.0);
        when(cost.missionCost(anyString())).thenReturn(new ModelCostService.Summary(List.of()));
        when(executor.resumeAsync(anyString(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(executor.correctAsync(anyString(), anyInt(), anyString())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(missions.claimNextRound(anyString(), anyInt())).thenReturn(true);
    }

    private static MissionResponse mission(String id, MissionStatus status, String team) {
        return new MissionResponse(id, status, "TEST", 0, "", "", Instant.now(), null, team);
    }

    @Test void atStartupWorkingAgentsGoIdleAndADiscoveryOrphanIsInterrupted() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-D", MissionStatus.WAITING_AGENT_RESULTS, null)));
        service.onStartup();
        verify(missions).resetWorkingAgents();
        verify(executor).resumeAsync(eq("M-D"), argThat(p -> p.stage() == ResumePoint.Stage.INTERRUPT));
    }

    // Review Focus 5: una misión viva (lanzada por el fundador) no es huérfana.
    @Test void aLiveMissionIsNotAnOrphan() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT")));
        when(executor.isLive("M-1")).thenReturn(true);
        service.recoverAll();
        verify(executor, never()).resumeAsync(anyString(), any());
    }

    @Test void onlyOneDevelopmentMissionIsDispatchedAtATime() {
        when(missions.findAll(anyInt())).thenReturn(List.of(
                mission("M-1", MissionStatus.PLANNING, "TEAM-DEVELOPMENT"),
                mission("M-2", MissionStatus.EVALUATING, "TEAM-DEVELOPMENT")));
        when(missions.createdAt("M-1")).thenReturn(Optional.of(Instant.parse("2026-10-05T10:00:00Z")));
        when(missions.createdAt("M-2")).thenReturn(Optional.of(Instant.parse("2026-10-05T09:00:00Z")));
        service.recoverAll();
        verify(executor).resumeAsync(eq("M-2"), any());          // la más antigua
        verify(executor, never()).resumeAsync(eq("M-1"), any());
    }

    @Test void aFailedValidationWithApprovedRequirementsStartsACorrection() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.AWAITING_INVESTOR, "TEAM-DEVELOPMENT")));
        when(missions.lastValidationStatus("M-1")).thenReturn(Optional.of("FAILED"));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(missions.evidenceRound("M-1")).thenReturn(0);
        service.recoverAll();
        verify(recovery).addCorrectionRound(eq("M-1"), argThat(r -> r.round() == 1 && r.increment() == 1 && !r.byFounder()));
        verify(executor).correctAsync(eq("M-1"), eq(1), startsWith(CorrectionReport.HEADER));
    }

    @Test void unvalidatedCancelledOrWithoutRequirementsAreLeftAlone() {
        when(missions.findAll(anyInt())).thenReturn(List.of(
                mission("M-U", MissionStatus.AWAITING_INVESTOR, "TEAM-DEVELOPMENT"),
                mission("M-C", MissionStatus.CANCELLED, "TEAM-DEVELOPMENT"),
                mission("M-N", MissionStatus.FAILED, "TEAM-DEVELOPMENT")));
        when(missions.lastValidationStatus("M-U")).thenReturn(Optional.of("UNVALIDATED"));
        when(requirements.approved(anyString())).thenReturn(Optional.empty());
        service.recoverAll();
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString());
    }

    // Spec misiones durables §2: "reanuda" relanza una discovery INTERRUPTED como la primera vez.
    @Test void resumingAnInterruptedDiscoveryRunsItAgain() {
        when(missions.find("M-D")).thenReturn(Optional.of(mission("M-D", MissionStatus.INTERRUPTED, null)));
        when(missions.instructionOf("M-D")).thenReturn(Optional.of("busca un nicho"));
        service.resume("M-D");
        verify(executor).executeAsync("M-D", "busca un nicho");
    }

    @Test void onlyInterruptedMissionsCanBeResumed() {
        when(missions.find("M-1")).thenReturn(Optional.of(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT")));
        assertThrows(IllegalStateException.class, () -> service.resume("M-1"));
    }

    @Test void theCorrectionCounterBlocks() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false)));
        assertTrue(service.brake("M-1", 1, 2).orElseThrow().contains("3"));
    }

    @Test void theCostBlocks() {
        when(cost.missionCost("M-1")).thenReturn(new ModelCostService.Summary(List.of(/* una fila de 5,10 US$, ver ModelUsageMemoryService.Row */)));
        assertTrue(service.brake("M-1", 1, 2).orElseThrow().contains("US$"));
    }

    // Review Focus 3: dos rondas seguidas sin bajar lo que falla → BLOCKED (falla de infraestructura repetida).
    @Test void noProgressInTwoRoundsBlocks() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 4, false),
                new CorrectionRound(2, 1, "b", 4, false)));
        assertTrue(service.brake("M-1", 1, 4).orElseThrow().contains("sin avance"));
        assertTrue(service.brake("M-1", 1, 3).isEmpty());
    }

    @Test void aFounderRoundResetsTheCounter() {
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false),
                new CorrectionRound(4, 1, "fundador", 3, true)));
        assertTrue(service.brake("M-1", 1, 2).isEmpty());
    }

    @Test void aBrakeLeavesTheMissionBlocked() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT")));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 5, false),
                new CorrectionRound(2, 1, "b", 4, false), new CorrectionRound(3, 1, "c", 3, false)));
        service.recoverAll();
        verify(executor).block(eq("M-1"), contains("correcciones"));
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString());
    }

    @Test void aLostRoundClaimDoesNotStartACorrection() {
        when(missions.findAll(anyInt())).thenReturn(List.of(mission("M-1", MissionStatus.FAILED, "TEAM-DEVELOPMENT")));
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(requirements.currentIncrement("M-1")).thenReturn(1);
        when(missions.claimNextRound("M-1", 0)).thenReturn(false);
        service.recoverAll();
        verify(executor, never()).correctAsync(anyString(), anyInt(), anyString());
    }
}
```

En `theCostBlocks`, construir la fila con el constructor real de `ModelUsageMemoryService.Row` (leerlo) con `costUsd = 5.10`.

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest=MissionRecoveryServiceTest` → error de compilación.

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.recovery;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.TaskIds;
import com.aicompany.core.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * Spec misiones durables §1 y §3: al arrancar, al terminar cada misión y cada 5 minutos recupera, en orden, las huérfanas,
 * las INTERRUPTED que se retoman solas (Development Group) y las que fallaron (corrección automática con frenos). Una
 * misión de desarrollo a la vez. Nunca lanza: un error se registra y se sigue con la siguiente.
 */
@Service
public class MissionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(MissionRecoveryService.class);
    static final String DEVELOPMENT_TEAM = "TEAM-DEVELOPMENT";
    static final Set<MissionStatus> WORKING = EnumSet.of(MissionStatus.CREATED, MissionStatus.PLANNING,
            MissionStatus.DELEGATING, MissionStatus.RESEARCHING, MissionStatus.WAITING_AGENT_RESULTS,
            MissionStatus.EVALUATING, MissionStatus.CONSOLIDATING, MissionStatus.EXECUTING);

    private final MissionMemoryService missions;
    private final RequirementsMemoryService requirements;
    private final RecoveryMemoryService recovery;
    private final MissionExecutor executor;
    private final CompanyPolicyService policies;
    private final ModelCostService cost;
    private final Executor dispatcher;
    private final List<String> resumed = Collections.synchronizedList(new ArrayList<>());

    @org.springframework.beans.factory.annotation.Autowired
    public MissionRecoveryService(MissionMemoryService missions, RequirementsMemoryService requirements,
                                  RecoveryMemoryService recovery, MissionExecutor executor, CompanyPolicyService policies,
                                  ModelCostService cost) {
        this(missions, requirements, recovery, executor, policies, cost,
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    var thread = new Thread(r, "mission-recovery");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    MissionRecoveryService(MissionMemoryService missions, RequirementsMemoryService requirements,
                           RecoveryMemoryService recovery, MissionExecutor executor, CompanyPolicyService policies,
                           ModelCostService cost, Executor dispatcher) {
        this.missions = missions;
        this.requirements = requirements;
        this.recovery = recovery;
        this.executor = executor;
        this.policies = policies;
        this.cost = cost;
        this.dispatcher = dispatcher;
    }

    /** Spec §1: después de CompanyMemoryInitializer (@Order(0)), en un hilo propio para no demorar el arranque. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void onStartup() {
        dispatcher.execute(() -> {
            try {
                log.info("RECOVERY - {} agent(s) WORKING -> IDLE", missions.resetWorkingAgents());
            } catch (Exception ex) {
                log.warn("RECOVERY - could not reset working agents: {}", ex.getMessage());
            }
            recoverAll();
        });
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void periodicCheck() {
        dispatcher.execute(this::recoverAll);
    }

    /** Gancho de MissionExecutor al llegar a AWAITING_INVESTOR, FAILED o BLOCKED. */
    public void onMissionFinished(String missionId) {
        dispatcher.execute(this::recoverAll);
    }

    public List<String> resumedSinceStart() {
        return List.copyOf(resumed);
    }

    /** Recorre las misiones en el orden del spec §1; lanza como máximo una de desarrollo. */
    public synchronized void recoverAll() {
        List<MissionResponse> all;
        try {
            all = missions.findAll(200);
        } catch (Exception ex) {
            log.warn("RECOVERY - could not list missions: {}", ex.getMessage());
            return;
        }
        var byAge = Comparator.comparing((MissionResponse m) -> missions.createdAt(m.missionId()).orElse(Instant.EPOCH));
        var orphans = all.stream().filter(m -> WORKING.contains(m.status()) && !executor.isLive(m.missionId()))
                .sorted(byAge).toList();
        var developmentBusy = all.stream().anyMatch(m -> DEVELOPMENT_TEAM.equals(m.teamId()) && executor.isLive(m.missionId()));

        for (var m : orphans) {
            if (!DEVELOPMENT_TEAM.equals(m.teamId())) {
                safely(m.missionId(), () -> executor.resumeAsync(m.missionId(),
                        new ResumePoint(ResumePoint.Stage.INTERRUPT, 0, ResumePoint.RoundKind.ORIGINAL, null)));
            }
        }
        if (developmentBusy) {
            return;
        }
        for (var m : orphans) {
            if (DEVELOPMENT_TEAM.equals(m.teamId())) {
                if (dispatchResume(m)) {
                    return;
                }
            }
        }
        var failed = all.stream().filter(this::failedAndCorrectable).sorted(byAge).toList();
        for (var m : failed) {
            if (dispatchCorrection(m)) {
                return;
            }
        }
    }

    /**
     * A pedido del fundador ("reanuda MISSION-X"): una INTERRUPTED del Development Group se retoma desde su ResumePoint;
     * una de discovery u otro equipo se relanza como la primera vez (ronda 0) o con el último pedido (ronda de evidencia).
     */
    public synchronized void resume(String missionId) {
        var mission = missions.find(missionId).orElseThrow(() -> new IllegalArgumentException("No existe la misión " + missionId));
        if (mission.status() != MissionStatus.INTERRUPTED) {
            throw new IllegalStateException("MISSION " + missionId + " no está INTERRUPTED (estado: " + mission.status() + ").");
        }
        if (executor.isLive(missionId)) {
            throw new IllegalStateException("MISSION " + missionId + " ya se está ejecutando.");
        }
        if (DEVELOPMENT_TEAM.equals(mission.teamId())) {
            if (!dispatchResume(mission)) {
                throw new IllegalStateException("No se pudo retomar " + missionId + " (ver el log).");
            }
            return;
        }
        var instruction = missions.instructionOf(missionId).orElse("");
        var round = missions.evidenceRound(missionId);
        if (round == 0) {
            executor.executeAsync(missionId, instruction);
        } else {
            var requests = missions.evidenceRequests(missionId);
            executor.reexecuteAsync(missionId, instruction, round,
                    requests.isEmpty() ? CeoService.NO_INVESTOR_COMMENT : requests.get(requests.size() - 1));
        }
        resumed.add(missionId);
    }

    private boolean dispatchResume(MissionResponse mission) {
        if (executor.isLive(mission.missionId())) {
            return false;
        }
        try {
            executor.resumeAsync(mission.missionId(), ResumePoint.of(snapshot(mission)));
            resumed.add(mission.missionId());
            return true;
        } catch (Exception ex) {
            log.warn("RECOVERY - could not resume {}: {}", mission.missionId(), ex.getMessage());
            return false;
        }
    }

    boolean failedAndCorrectable(MissionResponse m) {
        if (!DEVELOPMENT_TEAM.equals(m.teamId()) || executor.isLive(m.missionId())) {
            return false;
        }
        var failed = m.status() == MissionStatus.FAILED || (m.status() == MissionStatus.AWAITING_INVESTOR
                && missions.lastValidationStatus(m.missionId()).filter("FAILED"::equals).isPresent());
        return failed && requirements.approved(m.missionId()).isPresent();
    }

    private boolean dispatchCorrection(MissionResponse m) {
        var missionId = m.missionId();
        try {
            var increment = Math.max(1, requirements.currentIncrement(missionId));
            var report = CorrectionReport.from(lastValidation(missionId), m.message());
            var brake = brake(missionId, increment, report.failing());
            if (brake.isPresent()) {
                executor.block(missionId, brake.get() + "\n\nSigue fallando:\n" + report.text());
                return false;
            }
            var current = missions.evidenceRound(missionId);
            if (!missions.claimNextRound(missionId, current)) {
                return false;
            }
            var round = current + 1;
            recovery.addCorrectionRound(missionId, new CorrectionRound(round, increment, report.text(), report.failing(), false));
            executor.correctAsync(missionId, round, report.text());
            return true;
        } catch (Exception ex) {
            log.warn("RECOVERY - could not start the correction of {}: {}", missionId, ex.getMessage());
            return false;
        }
    }

    /** Spec §3: contador por incremento (desde la última ronda del fundador), costo de la misión y "sin avance". */
    public Optional<String> brake(String missionId, int increment, int failingNow) {
        var rounds = recovery.correctionRounds(missionId).stream().filter(r -> r.increment() == increment).toList();
        var lastFounder = -1;
        for (int i = 0; i < rounds.size(); i++) {
            if (rounds.get(i).byFounder()) {
                lastFounder = i;
            }
        }
        var auto = rounds.subList(lastFounder + 1, rounds.size()).stream().filter(r -> !r.byFounder()).toList();
        var max = (int) policies.activeValue(PolicyKey.MAX_AUTO_CORRECTIONS);
        if (auto.size() >= max) {
            return Optional.of("Se agotaron las " + max + " correcciones automáticas del incremento " + increment + ".");
        }
        var spent = cost.missionCost(missionId).totalUsd();
        var maxCost = policies.activeValue(PolicyKey.MAX_MISSION_COST_USD);
        if (spent >= maxCost) {
            return Optional.of(String.format(Locale.ROOT, "La misión gastó US$ %.2f en modelos (máximo US$ %.2f).", spent, maxCost));
        }
        if (auto.size() >= 2) {
            var before = auto.get(auto.size() - 2).failing();
            var last = auto.get(auto.size() - 1).failing();
            if (last >= before && failingNow >= last) {
                return Optional.of("Dos rondas de corrección seguidas sin avance (" + before + " → " + last + " → "
                        + failingNow + " cosas que fallan).");
            }
        }
        return Optional.empty();
    }

    MissionSnapshot snapshot(MissionResponse mission) {
        var missionId = mission.missionId();
        var round = missions.evidenceRound(missionId);
        var incrementRounds = requirements.incrementRoundsByNumber(missionId);
        Integer increment = incrementRounds != null && incrementRounds.containsKey(round) ? incrementRounds.get(round)
                : round == 0 ? Math.max(1, requirements.currentIncrement(missionId)) : null;
        var correction = recovery.correctionRound(missionId, round).isPresent();
        var latest = requirements.latest(missionId);
        var tasks = missions.tasks(missionId).stream().filter(t -> "WORK".equals(t.kind()))
                .filter(t -> TaskIds.roundOf(t.taskId()) == round)
                .map(t -> new MissionSnapshot.RoundTask(t.taskId(), t.status(), t.commitSha())).toList();
        var planTask = missions.tasks(missionId).stream().anyMatch(t -> "PLANNING".equals(t.kind())
                && "COMPLETED".equals(t.status()) && TaskIds.roundOf(t.taskId()) == round);
        return new MissionSnapshot(missionId, mission.teamId(), round, increment, correction,
                latest.map(RequirementsMemoryService.Version::status).orElse(null),
                requirements.approved(missionId).isPresent(), requirements.pendingAdjustment(missionId).orElse(null),
                planTask || !tasks.isEmpty(), tasks);
    }

    private Optional<AgentTask> lastValidation(String missionId) {
        return missions.tasks(missionId).stream().filter(t -> "VALIDATION".equals(t.kind()) && t.validationStatus() != null)
                .max(Comparator.comparing(AgentTask::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private void safely(String missionId, Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("RECOVERY - {} failed: {}", missionId, ex.getMessage());
        }
    }
}
```

Notas para el implementador:
- `snapshot` en una ronda de evidencia/corrección que reutiliza el plan guardado no tiene tarea `PLANNING` de esa ronda: "hay plan" = tarea `PLANNING` de la ronda **o** ya existen tareas `WORK` de la ronda (eso significa que la estrategia arrancó con un plan). Para `PLAN` de una ronda de evidencia/corrección, `resumeRound` usa `reexecuteTeamMission`, que ya decide entre plan guardado y replanificar.
- `TaskIds.roundOf` existe (lo usa el chat); verificar que devuelve 0 para ids sin `-R<n>`.
- `CompanyMemoryInitializer.initializeAfterReady`: agregar `@org.springframework.core.annotation.Order(0)`.
- `MissionExecutor`: setter `@Lazy` como el del orquestador, y en `advanceMission`, junto a `notifyOrchestrator`, para `AWAITING_INVESTOR`, `FAILED` y `BLOCKED`:

```java
    private com.aicompany.core.recovery.MissionRecoveryService recoveryService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecoveryService(@org.springframework.context.annotation.Lazy
                                   com.aicompany.core.recovery.MissionRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }
    // en advanceMission:
        if ((status == MissionStatus.AWAITING_INVESTOR || status == MissionStatus.FAILED || status == MissionStatus.BLOCKED)
                && recoveryService != null) {
            try {
                recoveryService.onMissionFinished(missionId);
            } catch (Exception ex) {
                log.warn("MISSION {} - recovery notification failed: {}", missionId, ex.getMessage());
            }
        }
```

Ojo: `onMissionFinished` corre en el hilo de la misión que termina, antes de que `runLive` la saque del registro; por eso despacha a su propio hilo (`dispatcher`), y `recoverAll` excluye las vivas. Una misión que termina y queda "falló" se corrige en el siguiente pase (chequeo de 5 min o fin de otra misión): agregar en `onMissionFinished` un segundo despacho con 5 s de espera:

```java
    public void onMissionFinished(String missionId) {
        java.util.concurrent.CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS, dispatcher)
                .execute(this::recoverAll);
    }
```

(y en el test, `Runnable::run` como dispatcher no aplica a `delayedExecutor`: `onMissionFinished` no se prueba unitariamente; se verifica en vivo.)

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn test -Dtest='MissionRecoveryServiceTest,MissionExecutorTest,MissionExecutorTeamTest'` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app && git commit -m "Durables: MissionRecoveryService recupera en orden con frenos"
```

---

### Task 10: Decisiones del fundador: `BLOCKED` y "reanuda"

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java` (`recordDecision` ~280-375)
- Modify: `app/src/main/java/com/aicompany/core/controller/MissionController.java`
- Test: `app/src/test/java/com/aicompany/core/service/MissionServiceTest.java`, `app/src/test/java/com/aicompany/core/controller/MissionControllerRequirementsTest.java`

**Interfaces:**
- Consumes: `RecoveryMemoryService.addCorrectionRound`, `CorrectionReport.from`, `MissionExecutor.correctAsync`, `MissionRecoveryService.resume`.
- Produces: `POST /api/company/missions/{id}/resume` → 200 con `MissionResponse`.

- [ ] **Step 1: Tests que fallan** (en el bloque de rondas de `MissionServiceTest`: `roundService`, `roundMemory`, `roundExecutor`, `policies`)

```java
// Spec misiones durables §3: sobre una BLOCKED, "más evidencia" sigue con la indicación sin gastar rondas de evidencia.
@Test
void aFounderIndicationOnABlockedMissionIsACorrectionRound() {
    var recovery = mock(com.aicompany.core.recovery.RecoveryMemoryService.class);
    roundService.setRecoveryMemory(recovery);
    when(roundMemory.find("MISSION-1")).thenReturn(Optional.of(mission("MISSION-1", MissionStatus.BLOCKED)));
    when(roundMemory.evidenceRound("MISSION-1")).thenReturn(5);
    when(policies.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS)).thenReturn(1.0);
    when(roundMemory.tasks("MISSION-1")).thenReturn(List.of());
    when(roundExecutor.correctAsync(anyString(), anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

    roundService.recordDecision("MISSION-1", new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "usa otra librería"));

    verify(roundMemory).claimNextRound("MISSION-1", 5);
    verify(recovery).addCorrectionRound(eq("MISSION-1"), argThat(r -> r.round() == 6 && r.byFounder()));
    verify(roundExecutor).correctAsync(eq("MISSION-1"), eq(6), argThat(s -> s.startsWith("INDICACIÓN DEL FUNDADOR: usa otra librería")));
    verify(roundExecutor, never()).reexecuteAsync(any(), any(), anyInt(), any());
}

@Test
void aBlockedMissionCanBeApprovedOrRejected() {
    when(roundMemory.find("MISSION-1")).thenReturn(Optional.of(mission("MISSION-1", MissionStatus.BLOCKED)));
    roundService.recordDecision("MISSION-1", new DecisionCommand(InvestorDecision.APPROVE, "así está bien"));
    verify(roundMemory).updateMission(eq("MISSION-1"), eq(MissionStatus.COMPLETED), anyInt(), anyString(), anyString());
}
```

En `MissionControllerRequirementsTest` (mismo armado con `MockMvc` que los tests de requisitos):

```java
@Test
void resumeIsAnEndpoint() throws Exception {
    when(missionService.status("M-9")).thenReturn(Optional.of(/* MissionResponse INTERRUPTED de M-9 */));
    mvc.perform(post("/api/company/missions/M-9/resume")).andExpect(status().isOk());
    verify(recoveryService).resume("M-9");
}
```

(`recoveryService` es un mock nuevo de `MissionRecoveryService` inyectado en el controller del test.)

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest='MissionServiceTest,MissionControllerRequirementsTest'` → fallan.

- [ ] **Step 3: Implementar**

`MissionService.recordDecision`:
- El chequeo de estado acepta `BLOCKED` además de `AWAITING_INVESTOR` y `FAILED` (mensaje: "…en AWAITING_INVESTOR, FAILED o BLOCKED…").
- En el bloque de `REQUEST_MORE_EVIDENCE`, si el estado es `BLOCKED`: saltar el chequeo de `MAX_EVIDENCE_ROUNDS` (comentario "Spec misiones durables §3: la indicación sobre una BLOCKED no consume rondas de evidencia"), reclamar la ronda igual (`claimNextRound`) y marcar `blockedIndication = true`.
- Al final, en lugar de `executor.reexecuteAsync(...)` cuando `blockedIndication`:

```java
        if (nextRound != null && blockedIndication) {
            var round = nextRound;
            var lastValidation = memory.tasks(missionId).stream()
                    .filter(t -> "VALIDATION".equals(t.kind()) && t.validationStatus() != null)
                    .max(java.util.Comparator.comparing(AgentTask::updatedAt,
                            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
            var report = com.aicompany.core.recovery.CorrectionReport.from(lastValidation, mission.get().message());
            var request = "INDICACIÓN DEL FUNDADOR: " + command.reasoning() + "\n\n" + report.text();
            var increment = requirementsMemory == null ? 1 : Math.max(1, requirementsMemory.currentIncrement(missionId));
            if (recoveryMemory != null) {
                recoveryMemory.addCorrectionRound(missionId,
                        new com.aicompany.core.recovery.CorrectionRound(round, increment, request, report.failing(), true));
            }
            executor.correctAsync(missionId, round, request);
        } else if (nextRound != null) {
            // ... reexecuteAsync de siempre
        }
```

`MissionController`:

```java
    private final com.aicompany.core.recovery.MissionRecoveryService recoveryService;   // por constructor

    /** Spec misiones durables §1: retoma una misión INTERRUPTED del Development Group. */
    @PostMapping("/{missionId}/resume")
    public ResponseEntity<MissionResponse> resume(@PathVariable String missionId) {
        recoveryService.resume(missionId);
        return missionService.status(missionId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn test -Dtest='MissionServiceTest,MissionServiceRequirementsTest,MissionControllerRequirementsTest'` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app && git commit -m "Durables: decisiones sobre BLOCKED y endpoint para reanudar"
```

---

### Task 11: Chat

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java` (gobernanza ~350-375, `routeInternal` ~640-680, `formatCompanyStatus` ~1870-1945, `formatEvidenceRounds` ~1362)
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `MissionRecoveryService.resume`, `resumedSinceStart`; `RecoveryMemoryService.correctionRounds`.

- [ ] **Step 1: Tests que fallan**

```java
// Spec misiones durables §4: "reanuda MISSION-X" es gobernanza y no toca al modelo.
@Test
void resumeIsAGovernanceCommand() {
    var recovery = mock(com.aicompany.core.recovery.MissionRecoveryService.class);
    router.setRecoveryService(recovery);
    var response = router.route("reanuda MISSION-9");
    verify(recovery).resume("MISSION-9");
    assertTrue(response.contains("Retomé MISSION-9"), response);
    verifyNoInteractions(ceoService);
}

@Test
void aRefusedResumeExplainsWhy() {
    var recovery = mock(com.aicompany.core.recovery.MissionRecoveryService.class);
    router.setRecoveryService(recovery);
    doThrow(new IllegalStateException("MISSION MISSION-9 no está INTERRUPTED")).when(recovery).resume("MISSION-9");
    assertTrue(router.route("reanuda MISSION-9").contains("no está INTERRUPTED"));
}

// Spec misiones durables §4: el status muestra retomadas, en corrección, bloqueadas e interrumpidas.
@Test
void theStatusShowsTheRecovery() {
    var recovery = mock(com.aicompany.core.recovery.MissionRecoveryService.class);
    var recoveryMemory = mock(com.aicompany.core.recovery.RecoveryMemoryService.class);
    router.setRecoveryService(recovery);
    router.setRecoveryMemory(recoveryMemory);
    when(recovery.resumedSinceStart()).thenReturn(List.of("MISSION-R"));
    when(missionMemory.findAll(50)).thenReturn(List.of(
            new MissionResponse("MISSION-C", MissionStatus.WAITING_AGENT_RESULTS, "TEST", 30, "x", "x", Instant.now(), null),
            new MissionResponse("MISSION-B", MissionStatus.BLOCKED, "TEST", 100, "x", "x", Instant.now(), null),
            new MissionResponse("MISSION-I", MissionStatus.INTERRUPTED, "TEST", 100, "x", "x", Instant.now(), null)));
    when(missionMemory.evidenceRound("MISSION-C")).thenReturn(2);
    when(recoveryMemory.correctionRounds("MISSION-C")).thenReturn(List.of(
            new com.aicompany.core.recovery.CorrectionRound(1, 1, "a", 3, false),
            new com.aicompany.core.recovery.CorrectionRound(2, 1, "b", 2, false)));
    when(companyPolicyService.activeValue(PolicyKey.MAX_AUTO_CORRECTIONS)).thenReturn(3.0);
    when(missionMemory.latestTaskPerAgent()).thenReturn(List.of());
    when(customerMemory.countCustomersAndProspects()).thenReturn(new long[]{0L, 0L});
    when(finance.summary(null)).thenReturn(new FinanceSummary(50, 0, 0, 0, 50, List.of()));

    var response = router.route("dame un status");

    assertTrue(response.contains("Retomadas tras el reinicio: MISSION-R"), response);
    assertTrue(response.contains("En corrección automática: MISSION-C (ronda 2 de 3)"), response);
    assertTrue(response.contains("Bloqueadas (esperan tu indicación): MISSION-B"), response);
    assertTrue(response.contains("Interrumpidas: MISSION-I"), response);
}
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest` → fallan.

- [ ] **Step 3: Implementar**

```java
    private static final Pattern RESUME = Pattern.compile("(?i)^\\s*(?:reanuda|retoma|reanudar|retomar)\\s+(" + MISSION_REF + ")\\b");

    private com.aicompany.core.recovery.MissionRecoveryService recoveryService;
    private com.aicompany.core.recovery.RecoveryMemoryService recoveryMemory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecoveryService(com.aicompany.core.recovery.MissionRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecoveryMemory(com.aicompany.core.recovery.RecoveryMemoryService recoveryMemory) {
        this.recoveryMemory = recoveryMemory;
    }

    /** Spec misiones durables §4: "reanuda MISSION-X" (gobernanza). */
    private String handleResume(String message) {
        var m = RESUME.matcher(normalize(message));
        if (!m.find() || recoveryService == null) {
            return null;
        }
        var missionId = m.group(1).toUpperCase(Locale.ROOT);
        try {
            recoveryService.resume(missionId);
            return "Retomé " + missionId + " donde quedó, sin gastar rondas. Te aviso cuando vuelva a esperar tu decisión.";
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return "No se pudo: " + ex.getMessage();
        }
    }

    /** Spec misiones durables §4: línea de la recuperación en "dame un status". */
    private String recoveryLine(List<MissionResponse> all) {
        var parts = new ArrayList<String>();
        if (recoveryService != null && !recoveryService.resumedSinceStart().isEmpty()) {
            parts.add("Retomadas tras el reinicio: " + String.join(", ", recoveryService.resumedSinceStart()) + ".");
        }
        if (recoveryMemory != null) {
            var max = (int) companyPolicyService.activeValue(PolicyKey.MAX_AUTO_CORRECTIONS);
            var correcting = all.stream().filter(m -> m.status() != MissionStatus.AWAITING_INVESTOR
                            && m.status() != MissionStatus.FAILED && m.status() != MissionStatus.COMPLETED
                            && m.status() != MissionStatus.CANCELLED && m.status() != MissionStatus.BLOCKED
                            && m.status() != MissionStatus.INTERRUPTED && m.status() != MissionStatus.AWAITING_SPEC_APPROVAL)
                    .flatMap(m -> {
                        var rounds = recoveryMemory.correctionRounds(m.missionId());
                        var round = missionMemory.evidenceRound(m.missionId());
                        var current = rounds.stream().filter(r -> r.round() == round).findFirst();
                        if (current.isEmpty()) {
                            return java.util.stream.Stream.<String>empty();
                        }
                        var n = rounds.stream().filter(r -> r.increment() == current.get().increment() && !r.byFounder()).count();
                        return java.util.stream.Stream.of(m.missionId() + " (ronda " + n + " de " + max + ")");
                    }).toList();
            if (!correcting.isEmpty()) {
                parts.add("En corrección automática: " + String.join(", ", correcting) + ".");
            }
        }
        var blocked = all.stream().filter(m -> m.status() == MissionStatus.BLOCKED).map(MissionResponse::missionId).toList();
        if (!blocked.isEmpty()) {
            parts.add("Bloqueadas (esperan tu indicación): " + String.join(", ", blocked) + ".");
        }
        var interrupted = all.stream().filter(m -> m.status() == MissionStatus.INTERRUPTED).map(MissionResponse::missionId).toList();
        if (!interrupted.isEmpty()) {
            parts.add("Interrumpidas: " + String.join(", ", interrupted) + " (\"reanuda <misión>\").");
        }
        return parts.isEmpty() ? "" : " " + String.join(" ", parts);
    }
```

- En `isGovernance(...)`: agregar `|| RESUME.matcher(normalize(message)).find()`.
- En el ruteo, justo antes de `var requirementsReply = handleRequirements(message);`:

```java
        var resumeReply = handleResume(message);
        if (resumeReply != null) {
            return resumeReply;
        }
```

- En `formatCompanyStatus`: `var recoveryLine = recoveryLine(all);` y sumarlo junto a `specLine + coverageLine`.
- En el conteo de "activas" de `formatCompanyStatus`, excluir también `BLOCKED` e `INTERRUPTED`.
- En `formatEvidenceRounds`: una ronda que está en `recoveryMemory.correctionRounds` se etiqueta `"Ronda " + r + " (corrección" + (byFounder ? " con tu indicación" : " automática") + ")"` y no cuenta en `evidenceRounds`.

- [ ] **Step 4: Correr y ver que pasan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app && git commit -m "Durables: el chat reanuda y muestra la recuperación"
```

---

### Task 12: Command Center

**Files:**
- Modify: `app/frontend/src/api/client.ts`, `app/frontend/src/pages/MissionDetailPage.tsx`

**Interfaces:**
- Consumes: `POST /api/company/missions/{id}/resume` (Task 10).

- [ ] **Step 1: Implementar**

`client.ts` (junto a `buildIncrement`):

```ts
  resumeMission: (missionId: string) =>
    request<MissionResponse>(`/api/company/missions/${missionId}/resume`, { method: 'POST' }),
```

`MissionDetailPage.tsx`:
- `DECIDABLE` = `new Set(['AWAITING_INVESTOR', 'FAILED', 'BLOCKED'])` y el texto "Solo se puede decidir sobre una misión en AWAITING_INVESTOR, FAILED o BLOCKED…".
- Componente nuevo, mostrado bajo el estado de la misión cuando `mission.status === 'INTERRUPTED'` o `'BLOCKED'`:

```tsx
/** Spec misiones durables §4: motivo de una BLOCKED/INTERRUPTED y "Reanudar" para las INTERRUPTED. */
function MissionRecovery({ mission }: { mission: MissionResponse }) {
  const queryClient = useQueryClient()
  const resume = useMutation({
    mutationFn: () => api.resumeMission(mission.missionId),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['missionDetails', mission.missionId] }),
  })
  if (mission.status !== 'INTERRUPTED' && mission.status !== 'BLOCKED') return null
  return (
    <section>
      <h2>{mission.status === 'BLOCKED' ? 'Bloqueada: espera tu indicación' : 'Interrumpida por un reinicio'}</h2>
      <pre className="mission-message">{mission.message}</pre>
      {mission.status === 'INTERRUPTED' && (
        <button type="button" disabled={resume.isPending} onClick={() => resume.mutate()}>
          Reanudar
        </button>
      )}
      {resume.isError && <p className="error">{(resume.error as Error).message}</p>}
    </section>
  )
}
```

(Usar la `queryKey` real con la que la página pide el detalle; leerla en el `useQuery` de la página.)

- [ ] **Step 2: Build y lint**

Run: `cd app/frontend && npm run build && npm run lint` → build OK, sin advertencias nuevas.

- [ ] **Step 3: Commit**

```bash
git add -A app/frontend && git commit -m "Durables: Missions muestra BLOCKED/INTERRUPTED y permite reanudar"
```

---

### Task 13: Documentación, suite completa y verificación en vivo

**Files:**
- Modify: `docs/EVENTS.md`, `docs/ARCHITECTURE.md`, `docs/HISTORY.md`, `docs/FALTANTES.md`, `CLAUDE.md`

- [ ] **Step 1: Docs**

- `docs/EVENTS.md`: los cuatro eventos nuevos con su origen y `data` (`RESUMED {stage, round, kind}`, `INTERRUPTED {}`, `CORRECTION_STARTED {round}`, `BLOCKED {reason}`).
- `docs/ARCHITECTURE.md`: subsección "Misiones durables y corrección automática" bajo "Flujo de misión" (qué se recupera, orden, `ResumePoint`, `CorrectionReport`, frenos, `BLOCKED`/`INTERRUPTED`, "reanuda").
- `docs/FALTANTES.md`: marcar `[x]` "Misiones durables" (sin cola) y "Corrección hasta cero errores" como parcial (núcleo hecho; pendiente estrategias con búsqueda web y log por error).
- `CLAUDE.md`: reemplazar el párrafo "**Antes de reconstruir/reiniciar el contenedor**…" por: "Las misiones del Development Group sobreviven un reinicio: al arrancar, `MissionRecoveryService` las retoma donde quedaron (las de discovery y otros equipos quedan `INTERRUPTED`: \"reanuda MISSION-X\"). Igual conviene no reiniciar a mitad de una llamada larga: esa llamada se repite." y agregar `INTERRUPTED`/`BLOCKED` en la máquina de estados.
- `docs/HISTORY.md`: entrada "Misiones durables y corrección automática (2026-10-xx)" con lo implementado (el resultado en vivo se agrega en el Step 3).

- [ ] **Step 2: Suite completa**

Run: `cd app && mvn test` → `BUILD SUCCESS`, 0 fallas.
Run: `cd app/frontend && npm run build && npm run lint` → OK.

- [ ] **Step 3: Verificación en vivo** (requiere desplegar; **pedir confirmación al fundador antes de cada reinicio** de `company-core`)

1. Desplegar (`docker compose build && docker compose up -d company-core`) sin misiones vivas.
2. Lanzar por chat una misión de prueba del Development Group (CRM simple de 3-5 escenarios), aprobar sus HU.
3. Cuando al menos una tarea `WORK` esté `COMPLETED` y otra `RUNNING`: reiniciar `company-core` (`docker compose restart company-core`).
4. Verificar: evento `EMPRESA_MISSION_RESUMED`, la tarea completada no se regenera (mismo `commitSha`), la misión termina; "dame un status" muestra "Retomadas tras el reinicio".
5. Repetir el reinicio durante la verificación (sandbox): vuelve a verificar y termina.
6. Si alguna misión llega con validación `FAILED`: observar la corrección automática (`EMPRESA_MISSION_CORRECTION_STARTED`) hasta `VERIFIED` o `BLOCKED` con su motivo.
7. Registrar los ids, tiempos, costo y resultado en `docs/HISTORY.md`.

- [ ] **Step 4: Commit**

```bash
git add -A docs CLAUDE.md && git commit -m "Docs: misiones durables y corrección automática"
```

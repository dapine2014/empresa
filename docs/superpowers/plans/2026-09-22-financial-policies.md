# Company Financial Policies + Mission.financialCriteria — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sacar las reglas financieras de negocio del hardcoding (`AppProperties`, `MissionExecutor`, `CustomerService`, `ContradictionDetector`) y convertirlas en Company Financial Policies versionadas y editables desde el Command Center; reemplazar el objetivo universal de Max por `Mission.financialCriteria`, estructurado y opcional, evaluado de forma determinista contra resultados reales.

**Architecture:** Nuevo `CompanyPolicyService` (Neo4j, mirror exacto de `PromptMemoryService`/`PromptVersion`) para 7 políticas numéricas fijas en código. Nuevas propiedades aplanadas y opcionales en `Mission` para `financialCriteria` (mismo criterio que `Mission.environment`). `MissionExecutor` resuelve ambas cosas en vivo para construir el objetivo de Max; `CustomerService.netProfit` evalúa cumplimiento contra resultados reales; `ChatIntentRouter` expone ambas al fundador. Frontend: sección "Financial Policies" en Settings + formulario "Iniciar misión" en Missions + display en MissionDetail.

**Tech Stack:** Spring Boot 4.1.1 / Java 21, `neo4j-java-driver` (Cypher a mano), Jackson 3 (`tools.jackson.*`), React 19 + TS + Vite + `@tanstack/react-query`.

**Spec:** `docs/superpowers/specs/2026-09-22-financial-policies-design.md`

## Global Constraints

- Las reglas de dominio deterministas (`netProfitUsd = revenueUsd - costUsd`, validación matemática de `Calculation`, gates de evidencia) **no se tocan** — no se vuelven configurables.
- El catálogo de `PolicyKey` es fijo en código (7 valores) — no un editor de esquema dinámico.
- `Mission.financialCriteria` es **inmutable una vez creada la misión** — no hay `PUT` para editarlo.
- **No hay parsing LLM de `financialCriteria` desde texto libre en esta ronda** — misiones por chat nacen con `financialCriteria = null`.
- Max nunca autodeclara cumplimiento del objetivo financiero — la evaluación es 100% código determinista (`CustomerService`).
- Ningún vencimiento de `deadline` dispara automatización — puramente informativo.
- "Las políticas evalúan decisiones, no modifican datos": ningún valor que reporte un agente se altera automáticamente para encajar con una política o un criterio.
- Convención de errores del proyecto: `IllegalArgumentException`/`IllegalStateException` sin captura fina → 500 vía el handler default de Spring. No introducir manejo de errores nuevo.
- Java records nuevos van en `com.aicompany.core.model`; servicios nuevos en `com.aicompany.core.service`.
- Cada task debe dejar `mvn test` en verde antes de pasar a la siguiente (los tasks 4, 5, 7 y 8 tocan firmas compartidas entre sí — el orden del plan ya evita builds rotos a mitad de camino).

---

### Task 1: `PolicyKey` + DTOs de Company Policy + schema Neo4j

**Files:**
- Create: `src/main/java/com/aicompany/core/model/PolicyKey.java`
- Create: `src/main/java/com/aicompany/core/model/PolicyVersionSummary.java`
- Create: `src/main/java/com/aicompany/core/model/PolicySnapshot.java`
- Create: `src/main/java/com/aicompany/core/model/PolicyCommand.java`
- Modify: `src/main/java/com/aicompany/core/service/CompanyMemoryService.java` (método `initializeSchema()`, junto a la línea `CREATE CONSTRAINT prompt_version_id ...`)

**Interfaces:**
- Produce: `PolicyKey` enum con exactamente `SEED_CAPITAL_USD, CHALLENGE_DAYS, CONTRADICTION_SEED_CAPITAL_MULTIPLE, SUCCESS_THRESHOLD_GOOD, SUCCESS_THRESHOLD_VERY_GOOD, SUCCESS_THRESHOLD_EXCELLENT, SUCCESS_THRESHOLD_EXTRAORDINARY` — usado por todos los tasks siguientes.
- Produce: `PolicySnapshot(String key, int activeVersion, double activeValue, String createdBy, String changeReason, Instant updatedAt, List<PolicyVersionSummary> history)`.

- [ ] **Step 1: Crear `PolicyKey`**

```java
package com.aicompany.core.model;

public enum PolicyKey {
    SEED_CAPITAL_USD,
    CHALLENGE_DAYS,
    CONTRADICTION_SEED_CAPITAL_MULTIPLE,
    SUCCESS_THRESHOLD_GOOD,
    SUCCESS_THRESHOLD_VERY_GOOD,
    SUCCESS_THRESHOLD_EXCELLENT,
    SUCCESS_THRESHOLD_EXTRAORDINARY
}
```

- [ ] **Step 2: Crear los 3 DTOs**

```java
package com.aicompany.core.model;

import java.time.Instant;

public record PolicyVersionSummary(
        int version,
        double value,
        String createdBy,
        String changeReason,
        Instant createdAt) {
}
```

```java
package com.aicompany.core.model;

import java.time.Instant;
import java.util.List;

public record PolicySnapshot(
        String key,
        int activeVersion,
        double activeValue,
        String createdBy,
        String changeReason,
        Instant updatedAt,
        List<PolicyVersionSummary> history) {
}
```

```java
package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record PolicyCommand(
        double value,
        @NotBlank String changeReason) {
}
```

- [ ] **Step 3: Agregar constraints de schema**

En `CompanyMemoryService.initializeSchema()`, junto a la línea de `prompt_version_id`:

```java
session.run("CREATE CONSTRAINT company_policy_key IF NOT EXISTS FOR (p:CompanyPolicy) REQUIRE p.key IS UNIQUE").consume();
session.run("CREATE CONSTRAINT policy_version_id IF NOT EXISTS FOR (v:PolicyVersion) REQUIRE v.id IS UNIQUE").consume();
```

- [ ] **Step 4: Compilar**

Run: `cd app && mvn -DskipTests compile`
Expected: BUILD SUCCESS (no hay tests propios para este step — son solo DTOs y una migración de schema, mismo criterio que `PromptVersion`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/PolicyKey.java \
        app/src/main/java/com/aicompany/core/model/PolicyVersionSummary.java \
        app/src/main/java/com/aicompany/core/model/PolicySnapshot.java \
        app/src/main/java/com/aicompany/core/model/PolicyCommand.java \
        app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java
git commit -m "Company Financial Policies: PolicyKey, DTOs y constraints de Neo4j"
```

---

### Task 2: `CompanyPolicyService`

**Files:**
- Create: `src/main/java/com/aicompany/core/service/CompanyPolicyService.java`
- Modify: `src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java`

**Interfaces:**
- Consume: `PolicyKey`, `PolicySnapshot`, `PolicyVersionSummary` (Task 1).
- Produce: `CompanyPolicyService.activeValue(PolicyKey)`, `.snapshotAll()`, `.snapshot(PolicyKey)`, `.createVersion(PolicyKey, double, String)`, `.activateVersion(PolicyKey, int)`, `.ensureDefaultPolicies()` — usados por Tasks 3, 7, 8, 9.

Mirror exacto de `PromptMemoryService` (`src/main/java/com/aicompany/core/service/PromptMemoryService.java`) adaptado a valores numéricos y una clave `PolicyKey` en vez de `agentId`. Sin test unitario propio — es una clase Neo4j-backed, mismo criterio que `PromptMemoryService`/`TeamMemoryService`/`MissionMemoryService` (ninguna tiene test directo en este repo); se verifica a través de `CompanyControllerTest` (Task 3) y la suite completa al final.

- [ ] **Step 1: Crear `CompanyPolicyService`**

```java
package com.aicompany.core.service;

import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.PolicySnapshot;
import com.aicompany.core.model.PolicyVersionSummary;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Company Financial Policies: parámetros de negocio de Forjai
 * (capital semilla, ventana de tiempo, umbrales de clasificación de
 * éxito, multiplicador de alerta de {@code ContradictionDetector}),
 * versionados y editables desde el Command Center. Mirror exacto de
 * {@link PromptMemoryService} -- mismo invariante duro: en todo
 * momento existe exactamente una relación {@code HAS_ACTIVE_POLICY}
 * por {@code CompanyPolicy}. Ver
 * docs/superpowers/specs/2026-09-22-financial-policies-design.md.
 *
 * <p>Catálogo fijo en código ({@link PolicyKey}): agregar una octava
 * política es un cambio de código, no de datos. Las reglas de dominio
 * deterministas ({@code netProfitUsd}, validación matemática de
 * {@code Calculation}, gates de evidencia) no viven acá -- nunca se
 * vuelven configurables.
 */
@Service
public class CompanyPolicyService {

    private final Driver driver;

    public CompanyPolicyService(Driver driver) {
        this.driver = driver;
    }

    private static final Map<PolicyKey, Double> DEFAULTS = defaults();

    private static Map<PolicyKey, Double> defaults() {
        var map = new LinkedHashMap<PolicyKey, Double>();
        map.put(PolicyKey.SEED_CAPITAL_USD, 50.0);
        map.put(PolicyKey.CHALLENGE_DAYS, 60.0);
        map.put(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE, 100.0);
        map.put(PolicyKey.SUCCESS_THRESHOLD_GOOD, 50.0);
        map.put(PolicyKey.SUCCESS_THRESHOLD_VERY_GOOD, 100.0);
        map.put(PolicyKey.SUCCESS_THRESHOLD_EXCELLENT, 1000.0);
        map.put(PolicyKey.SUCCESS_THRESHOLD_EXTRAORDINARY, 5000.0);
        return map;
    }

    /**
     * Idempotente: cualquier {@code PolicyKey} sin {@code HAS_ACTIVE_POLICY}
     * todavía recibe su versión 1 con el default histórico (los mismos
     * números que antes vivían hardcoded en {@code AppProperties}/
     * {@code MissionExecutor}/{@code CustomerService}/
     * {@code ContradictionDetector}). Llamado desde
     * {@code CompanyMemoryInitializer}.
     */
    public void ensureDefaultPolicies() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                for (var entry : DEFAULTS.entrySet()) {
                    tx.run(
                            "MERGE (p:CompanyPolicy {key:$key}) "
                                    + "WITH p WHERE NOT (p)-[:HAS_ACTIVE_POLICY]->(:PolicyVersion) "
                                    + "MERGE (v:PolicyVersion {id: $key + '-v1'}) "
                                    + "ON CREATE SET v.key = $key, v.version = 1, v.value = $value, "
                                    + "v.createdBy = 'system', v.changeReason = 'Valor inicial de seed', "
                                    + "v.createdAt = $createdAt "
                                    + "MERGE (p)-[:HAS_POLICY_VERSION]->(v) "
                                    + "MERGE (p)-[:HAS_ACTIVE_POLICY]->(v)",
                            Map.of(
                                    "key", entry.getKey().name(),
                                    "value", entry.getValue(),
                                    "createdAt", Instant.now().toString()));
                }
                return null;
            });
        }
    }

    /**
     * Hot path de lectura -- resuelto por {@code MissionExecutor}/
     * {@code CustomerService}/{@code ChatIntentRouter} antes de cada uso.
     */
    public double activeValue(PolicyKey key) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (:CompanyPolicy {key:$key})-[:HAS_ACTIVE_POLICY]->(v:PolicyVersion) "
                                    + "RETURN v.value AS value",
                            Map.of("key", key.name()))
                    .list(r -> r.get("value").asDouble())
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No hay política activa para " + key + " -- ¿faltó ensureDefaultPolicies()?"));
        }
    }

    public List<PolicySnapshot> snapshotAll() {
        var snapshots = new ArrayList<PolicySnapshot>();
        for (var key : PolicyKey.values()) {
            snapshots.add(snapshot(key));
        }
        return snapshots;
    }

    public PolicySnapshot snapshot(PolicyKey key) {
        try (var session = driver.session()) {

            var active = session.run(
                    "MATCH (:CompanyPolicy {key:$key})-[:HAS_ACTIVE_POLICY]->(v:PolicyVersion) "
                            + "RETURN v.version AS version, v.value AS value, "
                            + "v.createdBy AS createdBy, v.changeReason AS changeReason, v.createdAt AS createdAt",
                    Map.of("key", key.name())
            ).list();

            if (active.isEmpty()) {
                throw new IllegalStateException("No hay política activa para " + key);
            }

            var a = active.get(0);

            var history = session.run(
                    "MATCH (:CompanyPolicy {key:$key})-[:HAS_POLICY_VERSION]->(v:PolicyVersion) "
                            + "RETURN v.version AS version, v.value AS value, v.createdBy AS createdBy, "
                            + "v.changeReason AS changeReason, v.createdAt AS createdAt ORDER BY v.version DESC",
                    Map.of("key", key.name())
            ).list(r -> new PolicyVersionSummary(
                    r.get("version").asInt(),
                    r.get("value").asDouble(),
                    r.get("createdBy").asString(),
                    r.get("changeReason").asString(),
                    Instant.parse(r.get("createdAt").asString())
            ));

            return new PolicySnapshot(
                    key.name(),
                    a.get("version").asInt(),
                    a.get("value").asDouble(),
                    a.get("createdBy").asString(),
                    a.get("changeReason").asString(),
                    Instant.parse(a.get("createdAt").asString()),
                    history
            );
        }
    }

    /**
     * Crea una versión nueva y la activa en la misma transacción --
     * a diferencia del prompt de agentes, acá no hay un paso de
     * "borrador": editar una política es siempre efectivo de inmediato.
     */
    public PolicySnapshot createVersion(PolicyKey key, double value, String changeReason) {

        if (changeReason == null || changeReason.isBlank()) {
            throw new IllegalArgumentException("changeReason no puede estar vacío");
        }

        if (value <= 0) {
            throw new IllegalArgumentException("El valor de una política financiera debe ser positivo");
        }

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var result = tx.run(
                        "MATCH (p:CompanyPolicy {key:$key}) "
                                + "OPTIONAL MATCH (p)-[:HAS_POLICY_VERSION]->(existing:PolicyVersion) "
                                + "WITH p, coalesce(max(existing.version), 0) + 1 AS nextVersion "
                                + "CREATE (v:PolicyVersion {id: $key + '-v' + toString(nextVersion), "
                                + "key: $key, version: nextVersion, value: $value, "
                                + "createdBy: 'human', changeReason: $changeReason, createdAt: $createdAt}) "
                                + "MERGE (p)-[:HAS_POLICY_VERSION]->(v) "
                                + "WITH p, v "
                                + "OPTIONAL MATCH (p)-[old:HAS_ACTIVE_POLICY]->(:PolicyVersion) "
                                + "DELETE old "
                                + "CREATE (p)-[:HAS_ACTIVE_POLICY]->(v) "
                                + "RETURN v",
                        Map.of(
                                "key", key.name(), "value", value, "changeReason", changeReason,
                                "createdAt", Instant.now().toString()));

                if (result.list().isEmpty()) {
                    throw new IllegalArgumentException("No existe la política " + key);
                }

                return null;
            });
        }

        return snapshot(key);
    }

    /** Rollback: reactiva una versión existente, nunca crea contenido nuevo. */
    public PolicySnapshot activateVersion(PolicyKey key, int version) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var result = tx.run(
                        "MATCH (p:CompanyPolicy {key:$key})-[:HAS_POLICY_VERSION]->(v:PolicyVersion {version:$version}) "
                                + "OPTIONAL MATCH (p)-[old:HAS_ACTIVE_POLICY]->(:PolicyVersion) "
                                + "DELETE old "
                                + "CREATE (p)-[:HAS_ACTIVE_POLICY]->(v) "
                                + "RETURN v",
                        Map.of("key", key.name(), "version", version));

                if (result.list().isEmpty()) {
                    throw new IllegalArgumentException(
                            "No existe la versión " + version + " para la política " + key);
                }

                return null;
            });
        }

        return snapshot(key);
    }
}
```

- [ ] **Step 2: Wirear `ensureDefaultPolicies()` en el arranque**

En `CompanyMemoryInitializer.java`: agregar el campo/parámetro `CompanyPolicyService companyPolicyService` (mismo patrón que `promptMemory`) y llamar `companyPolicyService.ensureDefaultPolicies();` al final de `initializeAfterReady()`, después de `promptMemory.ensureDefaultPrompts();`.

- [ ] **Step 3: Compilar**

Run: `cd app && mvn -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CompanyPolicyService.java \
        app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java
git commit -m "Company Financial Policies: CompanyPolicyService (mirror de PromptMemoryService)"
```

---

### Task 3: Endpoints de Company Policy en `CompanyController`

**Files:**
- Modify: `src/main/java/com/aicompany/core/controller/CompanyController.java`
- Modify: `src/test/java/com/aicompany/core/controller/CompanyControllerTest.java`

**Interfaces:**
- Consume: `CompanyPolicyService` (Task 2), `PolicyKey`/`PolicySnapshot`/`PolicyCommand` (Task 1).
- Produce: `GET /api/company/policies`, `PUT /api/company/policies/{key}`, `PUT /api/company/policies/{key}/versions/{version}/activate`.

- [ ] **Step 1: Test — delegación de los 3 endpoints (RED)**

Agregar a `CompanyControllerTest.java`, junto a los tests de `agentPrompt*`:

```java
@Test
void policiesEndpointDelegatesEntirelyToCompanyPolicyServiceSnapshotAll() {
    var snapshot = new PolicySnapshot(
            "SEED_CAPITAL_USD", 1, 50.0, "system", "Valor inicial de seed", Instant.now(), List.of());
    when(companyPolicyService.snapshotAll()).thenReturn(List.of(snapshot));

    var response = controller.policies();

    assertEquals(List.of(snapshot), response);
}

@Test
void updatePolicyEndpointDelegatesEntirelyToCompanyPolicyServiceCreateVersion() {
    var updated = new PolicySnapshot(
            "SEED_CAPITAL_USD", 2, 200.0, "human", "Ronda de inversión", Instant.now(), List.of());
    when(companyPolicyService.createVersion(PolicyKey.SEED_CAPITAL_USD, 200.0, "Ronda de inversión"))
            .thenReturn(updated);

    var response = controller.updatePolicy("SEED_CAPITAL_USD", new PolicyCommand(200.0, "Ronda de inversión"));

    assertEquals(updated, response);
    verify(companyPolicyService).createVersion(PolicyKey.SEED_CAPITAL_USD, 200.0, "Ronda de inversión");
}

@Test
void activatePolicyVersionEndpointDelegatesEntirelyToCompanyPolicyServiceActivateVersion() {
    var reactivated = new PolicySnapshot(
            "SEED_CAPITAL_USD", 1, 50.0, "system", "Valor inicial de seed", Instant.now(), List.of());
    when(companyPolicyService.activateVersion(PolicyKey.SEED_CAPITAL_USD, 1)).thenReturn(reactivated);

    var response = controller.activatePolicyVersion("SEED_CAPITAL_USD", 1);

    assertEquals(reactivated, response);
    verify(companyPolicyService).activateVersion(PolicyKey.SEED_CAPITAL_USD, 1);
}
```

Agregar el mock y el nuevo parámetro del constructor:

```java
private final CompanyPolicyService companyPolicyService = mock(CompanyPolicyService.class);

private final CompanyController controller =
        new CompanyController(memory, missionMemory, activityMemory, router, teamMemory, promptMemory, companyPolicyService);
```

(actualizar también los imports: `com.aicompany.core.model.PolicyCommand`, `PolicyKey`, `PolicySnapshot`, `com.aicompany.core.service.CompanyPolicyService`)

- [ ] **Step 2: Run — verificar RED**

Run: `cd app && mvn test -Dtest=CompanyControllerTest`
Expected: FAIL (no compila — `CompanyController` todavía no tiene 7 parámetros ni los métodos `policies()`/`updatePolicy()`/`activatePolicyVersion()`)

- [ ] **Step 3: Implementar en `CompanyController`**

Agregar el campo/parámetro `CompanyPolicyService companyPolicyService` al constructor (mismo patrón que `promptMemoryService`), y estos 3 métodos junto a los de `agentPrompt`:

```java
/**
 * Las 7 Company Financial Policies vigentes -- panel "Settings" del
 * Command Center web, sección "Financial Policies".
 */
@GetMapping("/policies")
public List<PolicySnapshot> policies() {
    return companyPolicyService.snapshotAll();
}

/**
 * Crea una versión nueva de una política y la activa de inmediato --
 * a diferencia del prompt de agentes, acá no hay borrador.
 */
@PutMapping("/policies/{key}")
public PolicySnapshot updatePolicy(
        @PathVariable("key") String key,
        @Valid @RequestBody PolicyCommand command) {

    return companyPolicyService.createVersion(PolicyKey.valueOf(key), command.value(), command.changeReason());
}

/** Rollback: reactiva una versión existente del historial. */
@PutMapping("/policies/{key}/versions/{version}/activate")
public PolicySnapshot activatePolicyVersion(
        @PathVariable("key") String key,
        @PathVariable("version") int version) {

    return companyPolicyService.activateVersion(PolicyKey.valueOf(key), version);
}
```

(agregar imports `PolicyCommand`, `PolicyKey`, `PolicySnapshot`, `CompanyPolicyService`)

- [ ] **Step 4: Run — verificar GREEN**

Run: `cd app && mvn test -Dtest=CompanyControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/controller/CompanyController.java \
        app/src/test/java/com/aicompany/core/controller/CompanyControllerTest.java
git commit -m "Company Financial Policies: endpoints GET/PUT /api/company/policies"
```

---

### Task 4: `ContradictionDetector` — multiplicador como parámetro explícito

**Files:**
- Modify: `src/main/java/com/aicompany/core/agent/validation/ContradictionDetector.java`
- Modify: `src/test/java/com/aicompany/core/agent/validation/ContradictionDetectorTest.java`
- Modify: `src/main/java/com/aicompany/core/service/MissionExecutor.java` (un solo call site, valor literal temporal — Task 8 lo reemplaza por el valor real de la política)
- Modify: `src/test/java/com/aicompany/core/service/MissionExecutorTest.java` (arity de los mocks de `contradictionDetector.detect(...)`)

**Interfaces:**
- Produce: `ContradictionDetector.detect(List<AgentResult> results, double seedCapitalUsd, double seedCapitalMultipleThreshold)` — firma nueva, consumida por Task 8.

Se mantiene como función pura, sin dependencia a Neo4j/`CompanyPolicyService` — quien resuelve el multiplicador es `MissionExecutor`.

- [ ] **Step 1: Test — nueva firma con 3 argumentos (RED)**

En `ContradictionDetectorTest.java`, cambiar los 9 call sites `detector.detect(List.of(...), 50)` a `detector.detect(List.of(...), 50, 100)` (mismo valor 100 que hoy tiene `SEED_CAPITAL_MULTIPLE_THRESHOLD`, para no cambiar el comportamiento de ningún test existente).

- [ ] **Step 2: Run — verificar RED**

Run: `cd app && mvn test -Dtest=ContradictionDetectorTest`
Expected: FAIL (no compila, `detect` todavía toma 2 argumentos)

- [ ] **Step 3: Implementar la nueva firma**

En `ContradictionDetector.java`:
- Eliminar `private static final double SEED_CAPITAL_MULTIPLE_THRESHOLD = 100.0;`.
- Cambiar `detect(List<AgentResult> results, double seedCapitalUsd)` a `detect(List<AgentResult> results, double seedCapitalUsd, double seedCapitalMultipleThreshold)`, y su llamada interna a `detectMagnitudeOutliers(results, seedCapitalUsd, seedCapitalMultipleThreshold, contradictions)`.
- Cambiar `detectMagnitudeOutliers(List<AgentResult> results, double seedCapitalUsd, List<String> contradictions)` a `detectMagnitudeOutliers(List<AgentResult> results, double seedCapitalUsd, double seedCapitalMultipleThreshold, List<String> contradictions)`, reemplazando `SEED_CAPITAL_MULTIPLE_THRESHOLD` por `seedCapitalMultipleThreshold` en las 2 referencias internas (`var limit = seedCapitalUsd * seedCapitalMultipleThreshold;` y `(int) seedCapitalMultipleThreshold` en el mensaje de log).

- [ ] **Step 4: Actualizar el único call site real (temporal)**

En `MissionExecutor.java`, línea con `contradictionDetector.detect(agentResults, appProperties.seedCapitalUsd())`:

```java
var contradictions =
        contradictionDetector.detect(
                agentResults,
                appProperties.seedCapitalUsd(),
                100.0
        );
```

(El `100.0` es temporal — Task 8 lo reemplaza por `companyPolicyService.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)` cuando `MissionExecutor` gane esa dependencia. No es un placeholder de "TODO": es código válido y correcto en este punto intermedio, preserva el comportamiento actual exacto.)

- [ ] **Step 5: Actualizar arity de mocks en `MissionExecutorTest`**

En `MissionExecutorTest.java`, cambiar las 7 ocurrencias de `when(contradictionDetector.detect(any(), anyDouble())).thenReturn(...)` a `when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(...)`.

- [ ] **Step 6: Run — verificar GREEN**

Run: `cd app && mvn test -Dtest=ContradictionDetectorTest,MissionExecutorTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/ContradictionDetector.java \
        app/src/test/java/com/aicompany/core/agent/validation/ContradictionDetectorTest.java \
        app/src/main/java/com/aicompany/core/service/MissionExecutor.java \
        app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java
git commit -m "ContradictionDetector: multiplicador de alerta financiera como parámetro explícito"
```

---

### Task 5: `Mission.financialCriteria` — modelo y persistencia

**Files:**
- Create: `src/main/java/com/aicompany/core/model/FinancialMetric.java`
- Create: `src/main/java/com/aicompany/core/model/FinancialCriteriaCommand.java`
- Create: `src/main/java/com/aicompany/core/model/FinancialCriteriaResponse.java`
- Modify: `src/main/java/com/aicompany/core/model/MissionCommand.java`
- Modify: `src/main/java/com/aicompany/core/model/MissionResponse.java`
- Modify: `src/main/java/com/aicompany/core/service/MissionMemoryService.java`
- Modify: `src/main/java/com/aicompany/core/service/MissionService.java` (solo threading del parámetro nuevo, sin validación todavía — Task 6)
- Modify: `src/main/java/com/aicompany/core/controller/MissionController.java`
- Modify: `src/main/java/com/aicompany/core/service/ChatIntentRouter.java` (los 2 call sites de `missionService.start(...)`, agregar `null`)
- Modify: `src/test/java/com/aicompany/core/service/MissionServiceTest.java`
- Modify: `src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Produce: `MissionMemoryService.financialCriteria(String missionId) -> Optional<FinancialCriteriaResponse>` — consumido por Tasks 7 y 8.
- Produce: `MissionResponse` con 8vo campo `FinancialCriteriaResponse financialCriteria` — consumido por Tasks 7, 9, 10-13.
- Consume: nada de tasks previos (independiente de Company Policies).

Sin test unitario propio para los métodos Neo4j de `MissionMemoryService` (mismo criterio que el resto del archivo). Este task es principalmente plumbing mecánico: hay que mantener el build verde en cada paso.

- [ ] **Step 1: Crear `FinancialMetric` y los 2 DTOs**

```java
package com.aicompany.core.model;

public enum FinancialMetric {
    NET_PROFIT
}
```

```java
package com.aicompany.core.model;

import java.time.LocalDate;
import java.util.Locale;

public record FinancialCriteriaCommand(
        FinancialMetric metric,
        double targetAmount,
        String currency,
        LocalDate deadline) {

    public String currencyOrDefault() {
        return currency == null || currency.isBlank()
                ? "USD"
                : currency.toUpperCase(Locale.ROOT);
    }
}
```

```java
package com.aicompany.core.model;

import java.time.LocalDate;

public record FinancialCriteriaResponse(
        FinancialMetric metric,
        double targetAmount,
        String currency,
        LocalDate deadline) {
}
```

- [ ] **Step 2: Extender `MissionCommand` y `MissionResponse`**

En `MissionCommand.java`, agregar el campo (sin tocar `environmentOrDefault()`):

```java
public record MissionCommand(
        @NotBlank String missionId,
        @NotBlank String instruction,
        String environment,
        FinancialCriteriaCommand financialCriteria
) {
    public String environmentOrDefault() {
        return environment == null || environment.isBlank()
                ? "PRODUCTION"
                : environment.toUpperCase(Locale.ROOT);
    }
}
```

En `MissionResponse.java`, agregar el campo al final:

```java
public record MissionResponse(
        String missionId,
        MissionStatus status,
        String environment,
        int progress,
        String currentStep,
        String message,
        Instant updatedAt,
        FinancialCriteriaResponse financialCriteria
) {}
```

- [ ] **Step 3: `MissionMemoryService` — persistencia y lectura**

Reemplazar `ensureMission` y agregar el helper de parámetros y el lookup dedicado:

```java
public void ensureMission(
        String missionId,
        String instruction,
        String environment,
        FinancialCriteriaCommand financialCriteria) {

    try (var session = driver.session()) {
        session.executeWrite(tx -> {
            tx.run("MERGE (m:Mission {id:$id}) SET m.name=$name, m.instruction=$instruction, "
                            + "m.environment=$environment, m.status='CREATED', m.progress=0, "
                            + "m.currentStep='Creada', m.message='Misión recibida', m.updatedAt=$updatedAt, "
                            + "m.financialCriteriaMetric=$metric, m.financialCriteriaTargetAmount=$targetAmount, "
                            + "m.financialCriteriaCurrency=$currency, m.financialCriteriaDeadline=$deadline",
                    financialCriteriaParams(missionId, instruction, environment, financialCriteria));
            tx.run("MATCH (m:Mission {id:$id}), (c:Company {id:'AI-COMPANY'}) MERGE (c)-[:HAS_MISSION]->(m)", Map.of("id", missionId));
            tx.run("MATCH (m:Mission {id:$id}), (a:Agent {id:'ceo'}) MERGE (m)-[:LED_BY]->(a)", Map.of("id", missionId));
            return null;
        });
    }
}

/**
 * Neo4j: asignar {@code null} a una propiedad la remueve -- no hace
 * falta lógica condicional para "misión sin financialCriteria",
 * simplemente se pasan los 4 valores como {@code null}. {@code Map.of}
 * no admite valores {@code null}, por eso un {@code HashMap} mutable acá.
 */
private Map<String, Object> financialCriteriaParams(
        String missionId, String instruction, String environment, FinancialCriteriaCommand fc) {

    var params = new HashMap<String, Object>();
    params.put("id", missionId);
    params.put("name", missionId.equals("MISSION-001") ? "MISSION-001 — Descubrimiento del primer negocio" : missionId);
    params.put("instruction", instruction);
    params.put("environment", environment);
    params.put("updatedAt", Instant.now().toString());
    params.put("metric", fc == null ? null : fc.metric().name());
    params.put("targetAmount", fc == null ? null : fc.targetAmount());
    params.put("currency", fc == null ? null : fc.currencyOrDefault());
    params.put("deadline", fc == null || fc.deadline() == null ? null : fc.deadline().toString());
    return params;
}

private FinancialCriteriaResponse mapFinancialCriteria(org.neo4j.driver.Record r) {
    if (r.get("financialCriteriaMetric").isNull()) {
        return null;
    }
    var deadlineValue = r.get("financialCriteriaDeadline");
    return new FinancialCriteriaResponse(
            FinancialMetric.valueOf(r.get("financialCriteriaMetric").asString()),
            r.get("financialCriteriaTargetAmount").asDouble(),
            r.get("financialCriteriaCurrency").asString(),
            deadlineValue.isNull() ? null : LocalDate.parse(deadlineValue.asString())
    );
}

/**
 * Lookup dedicado y liviano para {@code MissionExecutor}/
 * {@code CustomerService} -- separado de {@link #find} para no
 * acoplar sus mocks de test al resto de {@code MissionResponse}.
 */
public Optional<FinancialCriteriaResponse> financialCriteria(String missionId) {
    try (var session = driver.session()) {
        var records = session.run(
                        "MATCH (m:Mission {id:$id}) RETURN m.financialCriteriaMetric AS financialCriteriaMetric, "
                                + "m.financialCriteriaTargetAmount AS financialCriteriaTargetAmount, "
                                + "m.financialCriteriaCurrency AS financialCriteriaCurrency, "
                                + "m.financialCriteriaDeadline AS financialCriteriaDeadline",
                        Map.of("id", missionId))
                .list();
        if (records.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapFinancialCriteria(records.get(0)));
    }
}
```

Agregar imports: `java.util.HashMap`, `java.time.LocalDate`, `com.aicompany.core.model.FinancialCriteriaCommand`, `com.aicompany.core.model.FinancialCriteriaResponse`, `com.aicompany.core.model.FinancialMetric`.

Extender el `RETURN`/mapper de `find`, `findByIds` y `findAll` con las mismas 4 columnas (`m.financialCriteriaMetric AS financialCriteriaMetric`, etc.) y pasar `mapFinancialCriteria(r)` como 8vo argumento de cada `new MissionResponse(...)`.

- [ ] **Step 4: `MissionService`/`MissionController`/`ChatIntentRouter` — threading del parámetro**

`MissionService.start`:
```java
public MissionResponse start(String missionId, String instruction, String environment, FinancialCriteriaCommand financialCriteria) {
    memory.ensureMission(missionId, instruction, environment, financialCriteria);
    ...
```
(sin validación todavía — Task 6)

`MissionController.start`:
```java
return ResponseEntity.accepted().body(
        missionService.start(command.missionId(), command.instruction(), command.environmentOrDefault(), command.financialCriteria())
);
```

`ChatIntentRouter.java`, en los 2 call sites `missionService.start(missionId, message, "PRODUCTION");` → `missionService.start(missionId, message, "PRODUCTION", null);` (misiones por chat en lenguaje libre no declaran criterio estructurado en esta ronda).

- [ ] **Step 5: Ripple mecánico en tests**

En `MissionServiceTest.java` y `ChatIntentRouterTest.java`: cada `new MissionResponse(missionId, status, environment, progress, step, message, updatedAt)` (7 args) pasa a 8 args agregando `, null` al final. Ejemplo concreto (aplicar la misma transformación en cada ocurrencia):

```java
// antes
new MissionResponse("MISSION-001", MissionStatus.CREATED, "PRODUCTION", 0, "Creada", "Misión recibida", Instant.parse("2026-09-12T00:00:00Z"))
// después
new MissionResponse("MISSION-001", MissionStatus.CREATED, "PRODUCTION", 0, "Creada", "Misión recibida", Instant.parse("2026-09-12T00:00:00Z"), null)
```

En `MissionServiceTest.startPersistsMissionAndSubmitsItForAsynchronousExecution`: el call site `service.start("MISSION-001", "Investigar una oportunidad", "PRODUCTION")` pasa a `service.start("MISSION-001", "Investigar una oportunidad", "PRODUCTION", null)`, y `verify(memory).ensureMission("MISSION-001", "Investigar una oportunidad", "PRODUCTION")` pasa a `verify(memory).ensureMission("MISSION-001", "Investigar una oportunidad", "PRODUCTION", null)`.

- [ ] **Step 6: Run — verificar GREEN**

Run: `cd app && mvn test -Dtest=MissionServiceTest,ChatIntentRouterTest,CompanyControllerTest`
Expected: PASS

Run: `cd app && mvn -DskipTests compile`
Expected: BUILD SUCCESS (confirma que no quedó ningún otro call site roto en `src/main`)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/FinancialMetric.java \
        app/src/main/java/com/aicompany/core/model/FinancialCriteriaCommand.java \
        app/src/main/java/com/aicompany/core/model/FinancialCriteriaResponse.java \
        app/src/main/java/com/aicompany/core/model/MissionCommand.java \
        app/src/main/java/com/aicompany/core/model/MissionResponse.java \
        app/src/main/java/com/aicompany/core/service/MissionMemoryService.java \
        app/src/main/java/com/aicompany/core/service/MissionService.java \
        app/src/main/java/com/aicompany/core/controller/MissionController.java \
        app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/test/java/com/aicompany/core/service/MissionServiceTest.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Mission.financialCriteria: modelo estructurado, opcional e inmutable"
```

---

### Task 6: Validación determinista de `financialCriteria` al crear la misión

**Files:**
- Modify: `src/main/java/com/aicompany/core/service/MissionService.java`
- Modify: `src/test/java/com/aicompany/core/service/MissionServiceTest.java`

**Interfaces:**
- Consume: `FinancialCriteriaCommand` (Task 5).

- [ ] **Step 1: Tests — 3 casos (RED)**

Agregar a `MissionServiceTest.java` (imports nuevos: `com.aicompany.core.model.FinancialCriteriaCommand`, `com.aicompany.core.model.FinancialMetric`, `java.time.LocalDate`):

```java
@Test
void startPersistsFinancialCriteriaWhenProvided() {
    var memory = mock(MissionMemoryService.class);
    var executor = mock(MissionExecutor.class);
    var eventPublisher = mock(CompanyEventPublisher.class);
    var criteria = new FinancialCriteriaCommand(FinancialMetric.NET_PROFIT, 1000.0, "USD", LocalDate.now().plusDays(30));
    var mission = new MissionResponse(
            "MISSION-001", MissionStatus.CREATED, "PRODUCTION", 0,
            "Creada", "Misión recibida", Instant.parse("2026-09-12T00:00:00Z"),
            new FinancialCriteriaResponse(FinancialMetric.NET_PROFIT, 1000.0, "USD", LocalDate.now().plusDays(30))
    );

    when(executor.executeAsync("MISSION-001", "Investigar una oportunidad"))
            .thenReturn(CompletableFuture.completedFuture(null));
    when(memory.find("MISSION-001")).thenReturn(Optional.of(mission));

    var service = new MissionService(memory, executor, eventPublisher);
    service.start("MISSION-001", "Investigar una oportunidad", "PRODUCTION", criteria);

    verify(memory).ensureMission("MISSION-001", "Investigar una oportunidad", "PRODUCTION", criteria);
}

@Test
void rejectsFinancialCriteriaWithNonPositiveTargetAmount() {
    var memory = mock(MissionMemoryService.class);
    var executor = mock(MissionExecutor.class);
    var eventPublisher = mock(CompanyEventPublisher.class);
    var criteria = new FinancialCriteriaCommand(FinancialMetric.NET_PROFIT, 0.0, "USD", null);

    var service = new MissionService(memory, executor, eventPublisher);

    assertThrows(IllegalArgumentException.class,
            () -> service.start("MISSION-001", "Investigar", "PRODUCTION", criteria));

    verify(memory, never()).ensureMission(any(), any(), any(), any());
}

@Test
void rejectsFinancialCriteriaWithDeadlineInThePast() {
    var memory = mock(MissionMemoryService.class);
    var executor = mock(MissionExecutor.class);
    var eventPublisher = mock(CompanyEventPublisher.class);
    var criteria = new FinancialCriteriaCommand(FinancialMetric.NET_PROFIT, 1000.0, "USD", LocalDate.now().minusDays(1));

    var service = new MissionService(memory, executor, eventPublisher);

    assertThrows(IllegalArgumentException.class,
            () -> service.start("MISSION-001", "Investigar", "PRODUCTION", criteria));

    verify(memory, never()).ensureMission(any(), any(), any(), any());
}
```

- [ ] **Step 2: Run — verificar RED**

Run: `cd app && mvn test -Dtest=MissionServiceTest`
Expected: FAIL (`rejectsFinancialCriteriaWith*` fallan porque hoy no hay validación — `ensureMission` se llama igual)

- [ ] **Step 3: Implementar validación en `MissionService.start`**

```java
public MissionResponse start(String missionId, String instruction, String environment, FinancialCriteriaCommand financialCriteria) {

    validateFinancialCriteria(financialCriteria);

    memory.ensureMission(missionId, instruction, environment, financialCriteria);
    ...
}

private void validateFinancialCriteria(FinancialCriteriaCommand financialCriteria) {

    if (financialCriteria == null) {
        return;
    }

    if (financialCriteria.metric() == null) {
        throw new IllegalArgumentException(
                "financialCriteria.metric es obligatorio si se declara un objetivo financiero");
    }

    if (financialCriteria.targetAmount() <= 0) {
        throw new IllegalArgumentException("financialCriteria.targetAmount debe ser mayor a 0");
    }

    if (financialCriteria.deadline() != null && financialCriteria.deadline().isBefore(java.time.LocalDate.now())) {
        throw new IllegalArgumentException("financialCriteria.deadline no puede ser anterior a hoy");
    }
}
```

- [ ] **Step 4: Run — verificar GREEN**

Run: `cd app && mvn test -Dtest=MissionServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/MissionService.java \
        app/src/test/java/com/aicompany/core/service/MissionServiceTest.java
git commit -m "Mission.financialCriteria: validación determinista al crear la misión"
```

---

### Task 7: `CustomerService` — migración a Company Policies + evaluación de `financialCriteria`

**Files:**
- Create: `src/main/java/com/aicompany/core/model/FinancialCriteriaEvaluation.java`
- Modify: `src/main/java/com/aicompany/core/model/MissionProfitResponse.java`
- Modify: `src/main/java/com/aicompany/core/service/CustomerService.java`
- Modify: `src/test/java/com/aicompany/core/service/CustomerServiceTest.java`

**Interfaces:**
- Consume: `CompanyPolicyService` (Task 2), `MissionMemoryService.financialCriteria` (Task 5).
- Produce: `MissionProfitResponse.financialCriteriaEvaluation` — consumido por Task 9 (chat) y Task 13 (frontend).

- [ ] **Step 1: Tests — migración de umbrales + evaluación (RED)**

En `CustomerServiceTest.java`: reemplazar el campo `AppProperties appProperties` por:

```java
private final CompanyPolicyService companyPolicyService = defaultCompanyPolicyService();

private static CompanyPolicyService defaultCompanyPolicyService() {
    var mock = mock(CompanyPolicyService.class);
    when(mock.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(50.0);
    when(mock.activeValue(PolicyKey.SUCCESS_THRESHOLD_GOOD)).thenReturn(50.0);
    when(mock.activeValue(PolicyKey.SUCCESS_THRESHOLD_VERY_GOOD)).thenReturn(100.0);
    when(mock.activeValue(PolicyKey.SUCCESS_THRESHOLD_EXCELLENT)).thenReturn(1000.0);
    when(mock.activeValue(PolicyKey.SUCCESS_THRESHOLD_EXTRAORDINARY)).thenReturn(5000.0);
    return mock;
}
```

En cada uno de los 6 `new CustomerService(memory, evidenceGate, appProperties)`, reemplazar por `new CustomerService(memory, evidenceGate, companyPolicyService, mock(MissionMemoryService.class))` (Mockito devuelve `Optional.empty()` por default para `financialCriteria(...)` sin stub explícito — no hace falta stubear nada más en los tests existentes).

Agregar 2 tests nuevos:

```java
@Test
void includesFinancialCriteriaEvaluationWhenMissionDeclaredOne() {
    var memory = mock(CustomerMemoryService.class);
    when(memory.totalRevenueAndCost("MISSION-001")).thenReturn(new double[]{1200.0, 100.0});

    var missionMemory = mock(MissionMemoryService.class);
    when(missionMemory.financialCriteria("MISSION-001")).thenReturn(Optional.of(
            new FinancialCriteriaResponse(FinancialMetric.NET_PROFIT, 1000.0, "USD", null)
    ));

    var service = new CustomerService(memory, evidenceGate, companyPolicyService, missionMemory);

    var profit = service.netProfit("MISSION-001");

    assertNotNull(profit.financialCriteriaEvaluation());
    assertTrue(profit.financialCriteriaEvaluation().criterionMet());
    assertEquals(110.0, profit.financialCriteriaEvaluation().progressPct(), 0.0001);
}

@Test
void financialCriteriaEvaluationIsAbsentWhenMissionHasNoDeclaredCriteria() {
    var memory = mock(CustomerMemoryService.class);
    when(memory.totalRevenueAndCost("MISSION-001")).thenReturn(new double[]{200.0, 50.0});

    var service = new CustomerService(memory, evidenceGate, companyPolicyService, mock(MissionMemoryService.class));

    var profit = service.netProfit("MISSION-001");

    assertNull(profit.financialCriteriaEvaluation());
}
```

(imports nuevos: `com.aicompany.core.model.FinancialCriteriaResponse`, `com.aicompany.core.model.FinancialMetric`, `com.aicompany.core.model.PolicyKey`, `com.aicompany.core.service.CompanyPolicyService`; quitar `import com.aicompany.core.config.AppProperties;`)

- [ ] **Step 2: Run — verificar RED**

Run: `cd app && mvn test -Dtest=CustomerServiceTest`
Expected: FAIL (no compila — `CustomerService` todavía toma `AppProperties`, no existe `financialCriteriaEvaluation()`)

- [ ] **Step 3: Crear `FinancialCriteriaEvaluation` y extender `MissionProfitResponse`**

```java
package com.aicompany.core.model;

import java.time.LocalDate;

public record FinancialCriteriaEvaluation(
        FinancialMetric metric,
        double targetAmount,
        String currency,
        LocalDate deadline,
        boolean criterionMet,
        double progressPct,
        Boolean deadlinePassed) {
}
```

```java
package com.aicompany.core.model;

public record MissionProfitResponse(
        String missionId,
        double totalRevenueUsd,
        double totalCostUsd,
        double netProfitUsd,
        double seedCapitalUsd,
        boolean successCriterionMet,
        String successLevel,
        FinancialCriteriaEvaluation financialCriteriaEvaluation
) {
}
```

- [ ] **Step 4: Reescribir `CustomerService`**

Reemplazar el campo `AppProperties appProperties` por `CompanyPolicyService companyPolicyService` y agregar `MissionMemoryService missionMemory` al constructor; eliminar los 4 `static final double` de umbrales; reescribir `netProfit`/`successLevel` y agregar `evaluate`:

```java
public MissionProfitResponse netProfit(String missionId) {

    var totals = memory.totalRevenueAndCost(missionId);
    var totalRevenue = totals[0];
    var totalCost = totals[1];
    var netProfit = totalRevenue - totalCost;
    var seedCapitalUsd = companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD);

    var successCriterionMet = netProfit > seedCapitalUsd;

    var evaluation = missionMemory.financialCriteria(missionId)
            .map(fc -> evaluate(fc, netProfit))
            .orElse(null);

    return new MissionProfitResponse(
            missionId, totalRevenue, totalCost, netProfit, seedCapitalUsd,
            successCriterionMet, successLevel(netProfit), evaluation
    );
}

private FinancialCriteriaEvaluation evaluate(FinancialCriteriaResponse criteria, double netProfit) {

    var criterionMet = netProfit >= criteria.targetAmount();
    var progressPct = criteria.targetAmount() > 0 ? (netProfit / criteria.targetAmount()) * 100 : 0;
    var deadlinePassed = criteria.deadline() == null
            ? null
            : (Boolean) java.time.LocalDate.now().isAfter(criteria.deadline());

    return new FinancialCriteriaEvaluation(
            criteria.metric(), criteria.targetAmount(), criteria.currency(), criteria.deadline(),
            criterionMet, progressPct, deadlinePassed
    );
}

private String successLevel(double netProfit) {

    if (netProfit >= companyPolicyService.activeValue(PolicyKey.SUCCESS_THRESHOLD_EXTRAORDINARY)) {
        return "EXTRAORDINARIO";
    }
    if (netProfit >= companyPolicyService.activeValue(PolicyKey.SUCCESS_THRESHOLD_EXCELLENT)) {
        return "EXCELENTE";
    }
    if (netProfit > companyPolicyService.activeValue(PolicyKey.SUCCESS_THRESHOLD_VERY_GOOD)) {
        return "MUY_BUENO";
    }
    if (netProfit > companyPolicyService.activeValue(PolicyKey.SUCCESS_THRESHOLD_GOOD)) {
        return "BUENO";
    }
    return "NINGUNO";
}
```

(mismos operadores `>=`/`>` que la versión original; imports nuevos: `com.aicompany.core.model.FinancialCriteriaEvaluation`, `com.aicompany.core.model.FinancialCriteriaResponse`, `com.aicompany.core.model.PolicyKey`; quitar el import de `AppProperties`)

- [ ] **Step 5: Run — verificar GREEN**

Run: `cd app && mvn test -Dtest=CustomerServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/FinancialCriteriaEvaluation.java \
        app/src/main/java/com/aicompany/core/model/MissionProfitResponse.java \
        app/src/main/java/com/aicompany/core/service/CustomerService.java \
        app/src/test/java/com/aicompany/core/service/CustomerServiceTest.java
git commit -m "CustomerService: migra a Company Policies y evalúa financialCriteria contra resultados reales"
```

---

### Task 8: `MissionExecutor` — reemplazo del objetivo hardcodeado de Max

**Files:**
- Modify: `src/main/java/com/aicompany/core/service/MissionExecutor.java`
- Modify: `src/test/java/com/aicompany/core/service/MissionExecutorTest.java`

**Interfaces:**
- Consume: `CompanyPolicyService` (Task 2), `MissionMemoryService.financialCriteria` (Task 5).

- [ ] **Step 1: Tests — 3 casos (RED)**

En `MissionExecutorTest.java`: quitar el campo `AppProperties appProperties` y su import; agregar:

```java
private final CompanyPolicyService companyPolicyService = defaultCompanyPolicyService();

private static CompanyPolicyService defaultCompanyPolicyService() {
    var mock = mock(CompanyPolicyService.class);
    when(mock.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(50.0);
    when(mock.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)).thenReturn(100.0);
    return mock;
}
```

Cambiar la construcción de `executor` para pasar `companyPolicyService` en la posición donde iba `appProperties`.

Reemplazar el test `financeObjectiveReferencesTheRealConfiguredSeedCapitalInsteadOfAHardcodedAmount` por:

```java
@Test
void financeObjectiveReferencesTheLiveSeedCapitalPolicyInsteadOfAHardcodedAmount() throws Exception {
    // Ya no lee AppProperties -- lee la Company Policy vigente. Valor
    // DISTINTO de 50 para probar que sale de la política real, no que
    // "coincide" con un default.
    var customPolicies = mock(CompanyPolicyService.class);
    when(customPolicies.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(75.0);
    when(customPolicies.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)).thenReturn(100.0);

    var executorWithCustomCapital = new MissionExecutor(
            memory, runtime, ceoService, companyMemory, promptMemory, "qwen2.5-coder:14b", Runnable::run, events,
            jsonMapper, contradictionDetector, customPolicies, opportunityMemory, alertMailService
    );

    stubAgent("sales");
    stubAgent("product");
    stubAgent("finance");
    stubAgent("engineering");
    stubAgent("qa");

    when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());
    when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");

    executorWithCustomCapital.executeAsync("MISSION-1", "instrucción").get();

    var instructionCaptor = ArgumentCaptor.forClass(String.class);
    verify(runtime).execute(anyString(), eq("MISSION-1"), eq("finance"), anyString(), instructionCaptor.capture());

    var expectedAmount = "US$%.2f".formatted(75.0);
    assertTrue(instructionCaptor.getValue().contains(expectedAmount));
    assertFalse(instructionCaptor.getValue().contains("US$50"));
}

@Test
void financeObjectiveIncludesMissionsStructuredFinancialCriteriaWhenDeclared() throws Exception {
    when(memory.financialCriteria("MISSION-1")).thenReturn(Optional.of(
            new FinancialCriteriaResponse(FinancialMetric.NET_PROFIT, 1000.0, "USD", java.time.LocalDate.of(2026, 11, 20))
    ));

    stubAgent("sales");
    stubAgent("product");
    stubAgent("finance");
    stubAgent("engineering");
    stubAgent("qa");

    when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());
    when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");

    executor.executeAsync("MISSION-1", "instrucción").get();

    var instructionCaptor = ArgumentCaptor.forClass(String.class);
    verify(runtime).execute(anyString(), eq("MISSION-1"), eq("finance"), anyString(), instructionCaptor.capture());

    var text = instructionCaptor.getValue();
    assertTrue(text.contains("objetivo financiero explícito"));
    assertTrue(text.contains("NET_PROFIT"));
    assertTrue(text.contains("1000"));
    assertTrue(text.contains("nunca alteres los valores"));
}

@Test
void financeObjectiveDoesNotAssertAnyTargetWhenMissionHasNoFinancialCriteria() throws Exception {
    stubAgent("sales");
    stubAgent("product");
    stubAgent("finance");
    stubAgent("engineering");
    stubAgent("qa");

    when(contradictionDetector.detect(any(), anyDouble(), anyDouble())).thenReturn(List.of());
    when(ceoService.executeMission(anyString(), anyString(), anyString(), anyString())).thenReturn("consolidado");

    executor.executeAsync("MISSION-1", "instrucción").get();

    var instructionCaptor = ArgumentCaptor.forClass(String.class);
    verify(runtime).execute(anyString(), eq("MISSION-1"), eq("finance"), anyString(), instructionCaptor.capture());

    assertFalse(instructionCaptor.getValue().contains("objetivo financiero explícito"));
}
```

(imports nuevos: `com.aicompany.core.model.FinancialCriteriaResponse`, `com.aicompany.core.model.FinancialMetric`, `com.aicompany.core.model.PolicyKey`, `com.aicompany.core.service.CompanyPolicyService`)

- [ ] **Step 2: Run — verificar RED**

Run: `cd app && mvn test -Dtest=MissionExecutorTest`
Expected: FAIL (no compila — `MissionExecutor` todavía toma `AppProperties`)

- [ ] **Step 3: Implementar en `MissionExecutor`**

Reemplazar el campo/parámetro `AppProperties appProperties` por `CompanyPolicyService companyPolicyService` (misma posición del constructor). En `executeInternal`, antes de construir `definitions`:

```java
var seedCapitalUsd = companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD);
var financialCriteria = memory.financialCriteria(missionId).orElse(null);
```

Reemplazar la entrada `"finance"` de `definitions` por:

```java
new AgentDefinition("finance", "UNIT_ECONOMICS", financeObjective(seedCapitalUsd, financialCriteria)),
```

Agregar el método:

```java
private String financeObjective(double seedCapitalUsd, FinancialCriteriaResponse financialCriteria) {

    var base = ("Estimar costos, precio, margen y condiciones de la oferta, "
            + "apoyándote en el capital semilla vigente de Forjai (US$%.2f) "
            + "como recurso disponible de la empresa — no como un monto de "
            + "utilidad que esta misión deba superar por defecto.")
            .formatted(seedCapitalUsd);

    if (financialCriteria == null) {
        return base;
    }

    var deadlineText = financialCriteria.deadline() == null
            ? "sin plazo definido"
            : "para " + financialCriteria.deadline();

    return base + " "
            + ("Esta misión tiene un objetivo financiero explícito: %s >= %.2f %s, %s. "
                    + "Analiza cómo alcanzarlo, pero nunca alteres los valores que reportes "
                    + "para forzar que el resultado coincida con este objetivo — tu análisis "
                    + "orienta la decisión, no reescribe los datos.")
            .formatted(
                    financialCriteria.metric(), financialCriteria.targetAmount(),
                    financialCriteria.currency(), deadlineText);
}
```

Reemplazar el call site de `ContradictionDetector` (Task 4 dejó un `100.0` temporal):

```java
var contradictions =
        contradictionDetector.detect(
                agentResults,
                seedCapitalUsd,
                companyPolicyService.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE)
        );
```

(`seedCapitalUsd` ya está en scope, resuelto arriba en el mismo método; imports nuevos: `com.aicompany.core.model.FinancialCriteriaResponse`, `com.aicompany.core.model.PolicyKey`, `com.aicompany.core.service.CompanyPolicyService`; quitar el import de `AppProperties`)

- [ ] **Step 4: Run — verificar GREEN**

Run: `cd app && mvn test -Dtest=MissionExecutorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/MissionExecutor.java \
        app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java
git commit -m "MissionExecutor: reemplaza el objetivo hardcodeado de Max por política + financialCriteria"
```

---

### Task 9: `ChatIntentRouter` — expone `financialCriteria` y migra a Company Policies

**Files:**
- Modify: `src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consume: `CompanyPolicyService` (Task 2), `CustomerService.netProfit` (Task 7), `MissionResponse.financialCriteria` (Task 5).

- [ ] **Step 1: Tests (RED)**

En `ChatIntentRouterTest.java`: reemplazar el campo `AppProperties appProperties` por `CompanyPolicyService companyPolicyService` (mock, con `when(mock.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(50.0);`) y agregar `CustomerService customerService = mock(CustomerService.class);` al constructor de `router` en la misma posición relativa. Actualizar el resto de los `new MissionResponse(...)` de este archivo (28 restantes tras Task 5) para que sigan compilando si alguno quedó sin el 8vo argumento.

Agregar 2 tests:

```java
@Test
void missionStatusQueryIncludesDeclaredFinancialCriteriaAndItsEvaluation() {
    var mission = new MissionResponse(
            "MISSION-42", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 90, "Consolidando",
            "informe final", Instant.parse("2026-09-12T00:00:00Z"),
            new FinancialCriteriaResponse(FinancialMetric.NET_PROFIT, 1000.0, "USD", null)
    );
    when(missionMemory.find("MISSION-42")).thenReturn(Optional.of(mission));
    when(missionMemory.tasks("MISSION-42")).thenReturn(List.of());
    when(missionMemory.latestTaskPerAgent()).thenReturn(List.of());
    when(productStatusService.resolve("MISSION-42")).thenReturn(ProductStatus.DISCOVERY);
    when(customerService.netProfit("MISSION-42")).thenReturn(new com.aicompany.core.model.MissionProfitResponse(
            "MISSION-42", 1200.0, 100.0, 1100.0, 50.0, true, "MUY_BUENO",
            new com.aicompany.core.model.FinancialCriteriaEvaluation(
                    FinancialMetric.NET_PROFIT, 1000.0, "USD", null, true, 110.0, null)
    ));

    var response = router.route("MISSION-42");

    assertTrue(response.contains("NET_PROFIT"));
    assertTrue(response.contains("objetivo cumplido"));
}

@Test
void companyStatusQueryReadsSeedCapitalFromTheLivePolicyInsteadOfAppProperties() {
    when(companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(200.0);
    when(missionMemory.findAll(50)).thenReturn(List.of());
    when(missionMemory.latestTaskPerAgent()).thenReturn(List.of());
    when(opportunityMemory.countOpportunities()).thenReturn(0);
    when(customerMemory.countCustomersAndProspects()).thenReturn(new int[]{0, 0});
    when(customerMemory.companyWideTotalRevenueAndCost()).thenReturn(new double[]{0.0, 0.0});

    var response = router.route("dame el estado de la empresa");

    assertTrue(response.contains("US$200"));
}
```

(la frase `"dame el estado de la empresa"` ya está verificada: matchea `normalized.contains("estado de la empresa")` en `detectQuery`, que resuelve a `QueryIntent.COMPANY_STATUS` → `formatCompanyStatus()` — no hace falta ajustar nada más)

- [ ] **Step 2: Run — verificar RED**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: FAIL

- [ ] **Step 3: Implementar en `ChatIntentRouter`**

Reemplazar el campo/parámetro `AppProperties appProperties` por `CompanyPolicyService companyPolicyService`, y agregar `CustomerService customerService` como nuevo parámetro del constructor.

Extender `formatMissionStatus`:

```java
private String formatMissionStatus(MissionResponse mission, List<AgentTask> tasks) {

    var productStatus = productStatusService.resolve(mission.missionId());
    var taskLines = tasks.stream()
            .map(t -> t.agentId() + "=" + t.action() + " " + t.status())
            .collect(Collectors.joining(", "));
    var involvedAgentIds = tasks.stream().map(AgentTask::agentId).collect(Collectors.toSet());
    var agentStatusLines = missionMemory.latestTaskPerAgent().stream()
            .filter(a -> involvedAgentIds.contains(a.agentId()))
            .map(a -> a.name() + " (" + a.role() + "): " + a.status())
            .collect(Collectors.joining(", "));
    var closing = productStatus.ordinal() < ProductStatus.DEVELOPMENT.ordinal()
            ? NO_DEVELOPMENT_EVIDENCE_DISCLAIMER_SINGLE
            : "";

    return mission.missionId() + ": workflowStatus=" + mission.status()
            + " (esto es el estado del proceso de análisis/decisión interno, "
            + "NO implica nada sobre si el producto está en desarrollo, publicado "
            + "o generando ingresos). productStatus=" + productStatus
            + ". Tareas de esta misión: " + taskLines
            + ". Estado actual de los agentes involucrados: " + agentStatusLines
            + "." + closing + formatFinancialCriteria(mission);
}

private String formatFinancialCriteria(MissionResponse mission) {

    if (mission.financialCriteria() == null) {
        return " Esta misión no tiene un objetivo financiero estructurado declarado.";
    }

    var criteria = mission.financialCriteria();
    var profit = customerService.netProfit(mission.missionId());
    var evaluation = profit.financialCriteriaEvaluation();
    var deadlineText = criteria.deadline() == null ? "sin plazo definido" : criteria.deadline().toString();

    if (evaluation == null) {
        return String.format(Locale.ROOT,
                " Objetivo financiero declarado: %s >= %.2f %s (%s). Sin resultados reales registrados "
                        + "todavía para evaluar cumplimiento.",
                criteria.metric(), criteria.targetAmount(), criteria.currency(), deadlineText);
    }

    return String.format(Locale.ROOT,
            " Objetivo financiero declarado: %s >= %.2f %s (%s). Resultado real: %.2f %s (%.1f%% del "
                    + "objetivo) -- %s.",
            criteria.metric(), criteria.targetAmount(), criteria.currency(), deadlineText,
            profit.netProfitUsd(), criteria.currency(), evaluation.progressPct(),
            evaluation.criterionMet() ? "objetivo cumplido" : "objetivo no cumplido todavía");
}
```

En `formatCompanyStatus`, cambiar `appProperties.seedCapitalUsd()` por `companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD)`.

(imports nuevos: `com.aicompany.core.model.PolicyKey`, `com.aicompany.core.service.CompanyPolicyService`; quitar el import de `AppProperties` si no se usa en ningún otro lado del archivo)

- [ ] **Step 4: Run — verificar GREEN**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "ChatIntentRouter: expone financialCriteria y migra capital semilla a Company Policy"
```

---

### Task 10: Frontend — tipos y cliente API

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/client.ts`

**Interfaces:**
- Produce: `FinancialMetric`, `FinancialCriteriaCommand`, `FinancialCriteriaResponse`, `FinancialCriteriaEvaluation`, `MissionProfitResponse`, `PolicyKey`, `PolicySnapshot`, `PolicyVersionSummary`, `PolicyCommand`, `MissionCommand`; `api.policies`, `api.updatePolicy`, `api.activatePolicyVersion`, `api.netProfit`, `api.startMission` — consumidos por Tasks 11, 12, 13.

- [ ] **Step 1: Extender `types.ts`**

Agregar al final del archivo, y agregar `financialCriteria: FinancialCriteriaResponse | null` a la interfaz `MissionResponse` existente:

```ts
export type FinancialMetric = 'NET_PROFIT'

export interface FinancialCriteriaCommand {
  metric: FinancialMetric
  targetAmount: number
  currency: string
  deadline: string | null
}

export interface FinancialCriteriaResponse {
  metric: FinancialMetric
  targetAmount: number
  currency: string
  deadline: string | null
}

export interface FinancialCriteriaEvaluation {
  metric: FinancialMetric
  targetAmount: number
  currency: string
  deadline: string | null
  criterionMet: boolean
  progressPct: number
  deadlinePassed: boolean | null
}

export interface MissionProfitResponse {
  missionId: string
  totalRevenueUsd: number
  totalCostUsd: number
  netProfitUsd: number
  seedCapitalUsd: number
  successCriterionMet: boolean
  successLevel: string
  financialCriteriaEvaluation: FinancialCriteriaEvaluation | null
}

export interface MissionCommand {
  missionId: string
  instruction: string
  environment: string
  financialCriteria: FinancialCriteriaCommand | null
}

// Company Financial Policy versionada -- ver GET/PUT /api/company/policies
export type PolicyKey =
  | 'SEED_CAPITAL_USD'
  | 'CHALLENGE_DAYS'
  | 'CONTRADICTION_SEED_CAPITAL_MULTIPLE'
  | 'SUCCESS_THRESHOLD_GOOD'
  | 'SUCCESS_THRESHOLD_VERY_GOOD'
  | 'SUCCESS_THRESHOLD_EXCELLENT'
  | 'SUCCESS_THRESHOLD_EXTRAORDINARY'

export interface PolicyVersionSummary {
  version: number
  value: number
  createdBy: string
  changeReason: string
  createdAt: string
}

export interface PolicySnapshot {
  key: PolicyKey
  activeVersion: number
  activeValue: number
  createdBy: string
  changeReason: string
  updatedAt: string
  history: PolicyVersionSummary[]
}

export interface PolicyCommand {
  value: number
  changeReason: string
}
```

- [ ] **Step 2: Extender `client.ts`**

Agregar a los imports de tipos: `FinancialCriteriaCommand` (no usado directo, se puede omitir si TS no lo exige), `MissionCommand`, `MissionProfitResponse`, `PolicyCommand`, `PolicySnapshot`. Agregar al objeto `api`:

```ts
  policies: () => request<PolicySnapshot[]>('/api/company/policies'),

  updatePolicy: (key: string, command: PolicyCommand) =>
    request<PolicySnapshot>(`/api/company/policies/${key}`, {
      method: 'PUT',
      body: JSON.stringify(command),
    }),

  activatePolicyVersion: (key: string, version: number) =>
    request<PolicySnapshot>(`/api/company/policies/${key}/versions/${version}/activate`, {
      method: 'PUT',
    }),

  netProfit: (missionId: string) =>
    request<MissionProfitResponse>(`/api/company/missions/${missionId}/net-profit`),

  startMission: (command: MissionCommand) =>
    request<MissionResponse>('/api/company/missions', {
      method: 'POST',
      body: JSON.stringify(command),
    }),
```

- [ ] **Step 3: Verificar**

Run: `cd app/frontend && npm run lint`
Expected: sin errores

Run: `cd app/frontend && npx tsc --noEmit` (si hay script de type-check; si no, `npm run build` cubre la verificación de tipos)
Expected: sin errores de tipos

- [ ] **Step 4: Commit**

```bash
git add app/frontend/src/api/types.ts app/frontend/src/api/client.ts
git commit -m "Frontend: tipos y cliente API para Financial Policies y financialCriteria"
```

---

### Task 11: Frontend — sección "Financial Policies" en Settings

**Files:**
- Modify: `frontend/src/pages/SettingsPage.tsx`

**Interfaces:**
- Consume: `api.policies`, `api.updatePolicy`, `api.activatePolicyVersion`, `PolicySnapshot` (Task 10).

- [ ] **Step 1: Agregar `PolicyRow` y la sección**

En `SettingsPage.tsx`, agregar antes de `export default function SettingsPage()`:

```tsx
import type { PolicySnapshot } from '../api/types'

const POLICY_LABELS: Record<string, string> = {
  SEED_CAPITAL_USD: 'Capital semilla (US$)',
  CHALLENGE_DAYS: 'Ventana de tiempo (días)',
  CONTRADICTION_SEED_CAPITAL_MULTIPLE: 'Multiplicador de alerta financiera (x capital semilla)',
  SUCCESS_THRESHOLD_GOOD: 'Umbral de éxito: Bueno (US$, >)',
  SUCCESS_THRESHOLD_VERY_GOOD: 'Umbral de éxito: Muy bueno (US$, >)',
  SUCCESS_THRESHOLD_EXCELLENT: 'Umbral de éxito: Excelente (US$, ≥)',
  SUCCESS_THRESHOLD_EXTRAORDINARY: 'Umbral de éxito: Extraordinario (US$, ≥)',
}

function PolicyRow({ policy }: { policy: PolicySnapshot }) {
  const queryClient = useQueryClient()
  const [value, setValue] = useState<number | null>(null)
  const [changeReason, setChangeReason] = useState('')
  const [expanded, setExpanded] = useState(false)

  const displayedValue = value ?? policy.activeValue

  const saveMutation = useMutation({
    mutationFn: () => api.updatePolicy(policy.key, { value: displayedValue, changeReason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies'] })
      setChangeReason('')
      setValue(null)
    },
  })

  const activateMutation = useMutation({
    mutationFn: (version: number) => api.activatePolicyVersion(policy.key, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['policies'] })
      setValue(null)
    },
  })

  return (
    <div className="policy-row">
      <div className="policy-row-main">
        <label>
          {POLICY_LABELS[policy.key] ?? policy.key}
          <input type="number" step="0.01" value={displayedValue} onChange={(e) => setValue(Number(e.target.value))} />
        </label>
        <input
          type="text"
          placeholder="Motivo del cambio"
          value={changeReason}
          onChange={(e) => setChangeReason(e.target.value)}
        />
        <button disabled={!changeReason.trim() || saveMutation.isPending} onClick={() => saveMutation.mutate()}>
          Guardar
        </button>
        <button type="button" onClick={() => setExpanded((v) => !v)}>
          {expanded ? 'Ocultar historial' : `Historial (v${policy.activeVersion})`}
        </button>
      </div>
      {saveMutation.isError && <p className="error">No se pudo guardar la política.</p>}
      {expanded && (
        <ul className="prompt-version-list">
          {policy.history.map((v) => (
            <li key={v.version}>
              <span>
                v{v.version} = {v.value} — {v.changeReason} ({new Date(v.createdAt).toLocaleString()})
              </span>
              {v.version !== policy.activeVersion && (
                <button disabled={activateMutation.isPending} onClick={() => activateMutation.mutate(v.version)}>
                  Activar
                </button>
              )}
              {v.version === policy.activeVersion && <span className="leader-tag">activa</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
```

Dentro de `SettingsPage`, agregar la query y la sección al final del JSX retornado (después del bloque de `feedback` existente, todavía dentro del `<div>` raíz):

```tsx
  const policiesQuery = useQuery({ queryKey: ['policies'], queryFn: api.policies })
```

```tsx
      <h2>Financial Policies</h2>
      <p className="hint">
        Reglas de negocio versionadas de Forjai — capital semilla, umbrales de éxito, ventana de tiempo. La
        fórmula de ganancia y la validación matemática de los agentes NO son editables acá: son reglas de
        dominio deterministas.
      </p>
      {policiesQuery.isLoading && <p>Cargando políticas...</p>}
      {policiesQuery.error && <p className="error">No se pudieron cargar las políticas.</p>}
      {policiesQuery.data?.map((policy) => <PolicyRow key={policy.key} policy={policy} />)}
```

- [ ] **Step 2: Verificar en el navegador**

Run: `cd app/frontend && npm run dev` (con el backend real corriendo en :8081, ver `mvn spring-boot:run` o Docker)
Manual: abrir `/settings`, confirmar que las 7 políticas se listan con su valor activo, editar una con un motivo de cambio, confirmar que la versión sube y el historial la muestra, y que "Activar" sobre una versión vieja hace rollback.

- [ ] **Step 3: Lint**

Run: `cd app/frontend && npm run lint`
Expected: sin errores

- [ ] **Step 4: Commit**

```bash
git add app/frontend/src/pages/SettingsPage.tsx
git commit -m "Frontend: sección Financial Policies en Settings"
```

---

### Task 12: Frontend — formulario "Iniciar misión" en Missions

**Files:**
- Modify: `frontend/src/pages/MissionsPage.tsx`

**Interfaces:**
- Consume: `api.startMission` (Task 10).

- [ ] **Step 1: Agregar `StartMissionForm`**

En `MissionsPage.tsx`, agregar antes de `export default function MissionsPage()`:

```tsx
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'

function StartMissionForm() {
  const queryClient = useQueryClient()
  const [instruction, setInstruction] = useState('')
  const [environment, setEnvironment] = useState<'PRODUCTION' | 'TEST'>('PRODUCTION')
  const [hasFinancialCriteria, setHasFinancialCriteria] = useState(false)
  const [targetAmount, setTargetAmount] = useState('')
  const [currency, setCurrency] = useState('USD')
  const [deadline, setDeadline] = useState('')
  const [feedback, setFeedback] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () =>
      api.startMission({
        missionId: `MISSION-${Date.now()}`,
        instruction,
        environment,
        financialCriteria: hasFinancialCriteria
          ? { metric: 'NET_PROFIT', targetAmount: Number(targetAmount), currency, deadline: deadline || null }
          : null,
      }),
    onSuccess: (response) => {
      setFeedback(`Misión ${response.missionId} creada.`)
      setInstruction('')
      setHasFinancialCriteria(false)
      setTargetAmount('')
      setDeadline('')
      queryClient.invalidateQueries({ queryKey: ['missions'] })
    },
    onError: () => setFeedback('No se pudo iniciar la misión.'),
  })

  return (
    <form
      className="decision-form"
      onSubmit={(e) => {
        e.preventDefault()
        setFeedback(null)
        mutation.mutate()
      }}
    >
      <h2>Iniciar misión</h2>
      <label>
        Instrucción
        <textarea required rows={3} value={instruction} onChange={(e) => setInstruction(e.target.value)} />
      </label>
      <label>
        Entorno
        <select value={environment} onChange={(e) => setEnvironment(e.target.value as 'PRODUCTION' | 'TEST')}>
          <option value="PRODUCTION">PRODUCTION</option>
          <option value="TEST">TEST</option>
        </select>
      </label>
      <label>
        <input type="checkbox" checked={hasFinancialCriteria} onChange={(e) => setHasFinancialCriteria(e.target.checked)} />
        Definir objetivo financiero
      </label>
      {hasFinancialCriteria && (
        <>
          <label>
            Utilidad neta objetivo
            <input type="number" required min="0.01" step="0.01" value={targetAmount} onChange={(e) => setTargetAmount(e.target.value)} />
          </label>
          <label>
            Moneda
            <input type="text" value={currency} onChange={(e) => setCurrency(e.target.value)} />
          </label>
          <label>
            Plazo (opcional)
            <input type="date" value={deadline} onChange={(e) => setDeadline(e.target.value)} />
          </label>
        </>
      )}
      <button type="submit" disabled={mutation.isPending || !instruction.trim()}>
        {mutation.isPending ? 'Iniciando...' : 'Iniciar misión'}
      </button>
      {feedback && <p className="feedback">{feedback}</p>}
    </form>
  )
}
```

Renderizar `<StartMissionForm />` al inicio del `<div>` retornado por `MissionsPage`, antes de la tabla existente.

- [ ] **Step 2: Verificar en el navegador**

Run: `cd app/frontend && npm run dev`
Manual: abrir `/missions`, crear una misión con objetivo financiero y otra sin él, confirmar que ambas aparecen en la tabla y que `financialCriteria` quedó bien persistido (chequear `GET /api/company/missions/{id}` o el detalle en Task 13).

- [ ] **Step 3: Lint**

Run: `cd app/frontend && npm run lint`

- [ ] **Step 4: Commit**

```bash
git add app/frontend/src/pages/MissionsPage.tsx
git commit -m "Frontend: formulario Iniciar misión con objetivo financiero opcional"
```

---

### Task 13: Frontend — `financialCriteria` en MissionDetail

**Files:**
- Modify: `frontend/src/pages/MissionDetailPage.tsx`

**Interfaces:**
- Consume: `api.netProfit`, `MissionResponse.financialCriteria` (Tasks 5, 10).

- [ ] **Step 1: Agregar la query y el bloque de display**

En `MissionDetailPage.tsx`, agregar junto a la query existente:

```tsx
  const netProfitQuery = useQuery({
    queryKey: ['netProfit', missionId],
    queryFn: () => api.netProfit(missionId!),
    enabled: !!missionId,
  })
```

Renderizar, después de `<p className="mission-message">{mission.message}</p>`:

```tsx
      {mission.financialCriteria && (
        <div className="financial-criteria">
          <h2>Objetivo financiero</h2>
          <p>
            {mission.financialCriteria.metric} ≥ {mission.financialCriteria.targetAmount} {mission.financialCriteria.currency}
            {mission.financialCriteria.deadline ? ` para ${mission.financialCriteria.deadline}` : ' (sin plazo definido)'}
          </p>
          {netProfitQuery.data?.financialCriteriaEvaluation && (
            <p>
              Resultado real: {netProfitQuery.data.netProfitUsd.toFixed(2)} {mission.financialCriteria.currency} (
              {netProfitQuery.data.financialCriteriaEvaluation.progressPct.toFixed(1)}% del objetivo) —{' '}
              {netProfitQuery.data.financialCriteriaEvaluation.criterionMet ? 'cumplido' : 'no cumplido todavía'}
            </p>
          )}
        </div>
      )}
```

- [ ] **Step 2: Verificar en el navegador**

Run: `cd app/frontend && npm run dev`
Manual: abrir el detalle de una misión creada con objetivo financiero en Task 12, confirmar que se muestra; registrar una `Transaction` real vía `POST /missions/{id}/transactions` (curl) y confirmar que el resultado real y el porcentaje se actualizan.

- [ ] **Step 3: Lint**

Run: `cd app/frontend && npm run lint`

- [ ] **Step 4: Commit**

```bash
git add app/frontend/src/pages/MissionDetailPage.tsx
git commit -m "Frontend: muestra financialCriteria y su evaluación en MissionDetail"
```

---

### Task 14: Documentación y verificación final

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/HISTORY.md`

- [ ] **Step 1: Actualizar `CLAUDE.md`**

Agregar una sección nueva "Company Financial Policies y Mission.financialCriteria" (después de la sección "Prompt versionado por agente"), documentando: las 7 políticas y su catálogo fijo, el patrón `CompanyPolicyService`/`PolicyVersion` (mirror de prompts), que `MissionExecutor` ya no tiene un objetivo universal, `Mission.financialCriteria` estructurado/opcional/inmutable, y los endpoints nuevos (`/api/company/policies`, financialCriteria en `POST /missions`). Actualizar la sección "Configuración" para aclarar que `company.seed-capital-usd`/`company.challenge-days` ahora son también solo default de seed del primer arranque (mismo criterio que `ollama.agent-model`).

- [ ] **Step 2: Agregar entrada en `docs/HISTORY.md`**

Registrar la decisión de alcance acordada con el usuario (las 3 preguntas de diseño: umbrales absolutos e independientes del capital semilla, financialCriteria estructurado en vez de texto libre, formulario de creación de misión incluido en esta ronda) y el resultado de la verificación en vivo del Step 4.

- [ ] **Step 3: Suite completa + build de frontend**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 0 failures

Run: `cd app/frontend && npm run lint && npm run build`
Expected: sin errores

- [ ] **Step 4: Verificación en vivo (Docker)**

Confirmar que no hay una misión real en curso (ver "Importante antes de reconstruir/reiniciar el contenedor" en `CLAUDE.md`), luego:

```bash
docker compose build && docker compose up -d
```

Manual: `GET /api/company/policies` devuelve las 7 políticas sembradas; editar `SEED_CAPITAL_USD` desde Settings y confirmar que `GET /api/company/settings`-adjacent... en realidad confirmar vía chat ("dame el estado de la empresa") que el capital disponible reportado cambió; iniciar una misión con objetivo financiero desde el formulario y confirmar en el log/Neo4j que Max recibió el bloque estructurado (buscar la tarea `UNIT_ECONOMICS` de esa misión).

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md docs/HISTORY.md
git commit -m "Documentar Company Financial Policies y Mission.financialCriteria en CLAUDE.md/HISTORY.md"
```

# Ledger Financiero Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar un libro contable real a Forjai — un balance de empresa que parte del capital semilla y suma/resta ventas reales (ya existentes) y gastos operativos (nuevos), expuesto vía API, chat y el Command Center web.

**Architecture:** `Expense` es la única entidad nueva persistida en Neo4j (gastos operativos, canal humano, misma validación de evidencia que `Customer`/`Transaction`). El ledger en sí **no se persiste** — `LedgerService` lo arma en memoria combinando `Transaction` (leído, no copiado) + `Expense` en cada request, calculando balance corriente solo sobre líneas `PRODUCTION`. Se expone vía `LedgerController` (REST), `ChatIntentRouter`/`CeoService` (chat, nuevo intent/topic `LEDGER` + balance agregado en `COMPANY_STATUS`) y una pantalla nueva del Command Center (`LedgerPage.tsx`).

**Tech Stack:** Spring Boot 4.1.1 / Java 21, `neo4j-java-driver` (Cypher a mano, sin Spring Data Neo4j), JUnit 5 + Mockito, React + Vite + TS + `@tanstack/react-query`.

**Spec:** `docs/superpowers/specs/2026-09-15-financial-ledger-design.md`

## Global Constraints

- Trabajar siempre dentro de `app/` para comandos Maven; `app/frontend/` para el frontend.
- Java 21, Spring Boot 4.1.1. Jackson es `tools.jackson.*` en el código de la app — no importar `com.fasterxml.jackson.*`.
- No reintroducir `@Async` en ninguna ruta de orquestación (no aplica a este trabajo, pero ningún archivo tocado debe agregarlo).
- Toda evidencia (`Customer`/`Transaction`/`Expense`) usa la misma forma `AgentResult.Evidence(description, source, sourceType, verified)` y pasa por el mismo `EvidenceValidationGate.validate(List<Evidence>)` — nunca un gate nuevo.
- `environment` (`PRODUCTION`/`TEST`) sigue el patrón ya establecido por `Mission`/`MissionCommand`: default `PRODUCTION` si se omite, nunca se adivina por el nombre de un id.
- Ningún registro financiero (`Customer`/`Transaction`/`Expense`) es editable ni borrable — inmutable una vez creado.
- Los montos en dólares son `double`; el formateo de texto usa `String.format(Locale.ROOT, "US$%.2f", ...)`, mismo patrón que el resto de `ChatIntentRouter`.
- Las consultas de chat se resuelven 100% en Java, nunca delegando el cálculo/conteo a Ollama.
- Tests: `mvn test -Dtest=Clase#metodo` desde `app/`; los `*MemoryService` que solo hacen I/O a Neo4j (Cypher puro, sin lógica propia) no llevan test directo — mismo criterio ya establecido en el proyecto para `CustomerMemoryService`/`OpportunityMemoryService`.
- Frontend sin framework de test automatizado — se valida con `npm run build` (type-check + bundle) y verificación manual en el navegador.

---

### Task 1: Persistencia y validación de `Expense`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/ExpenseCommand.java`
- Create: `app/src/main/java/com/aicompany/core/model/ExpenseResponse.java`
- Create: `app/src/main/java/com/aicompany/core/service/ExpenseMemoryService.java`
- Create: `app/src/main/java/com/aicompany/core/service/ExpenseService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java` (agregar constraint `expense_id`, junto a `transaction_id`)
- Test: `app/src/test/java/com/aicompany/core/service/ExpenseServiceTest.java`

**Interfaces:**
- Consumes: `com.aicompany.core.agent.model.AgentResult.Evidence(String description, String source, String sourceType, boolean verified)`; `com.aicompany.core.agent.validation.EvidenceValidationGate.validate(List<AgentResult.Evidence>)` → `ValidationResult` con `.valid()`/`.errors()` (ya existentes, sin cambios).
- Produces: `ExpenseService.registerExpense(ExpenseCommand command) -> ExpenseResponse` (lanza `IllegalArgumentException` si `missionId` viene y no existe, `IllegalStateException` si la evidencia es inválida). `ExpenseMemoryService.missionExists(String)`, `.create(double amountUsd, String description, String missionId, String environment, AgentResult.Evidence evidence) -> ExpenseResponse`. Estos dos nombres los usa la Task 2 (`ExpenseMemoryService.listAll()`, ver esa task) y la Task 3 (`ExpenseController`... en realidad `LedgerController`, ver Task 3).

- [ ] **Step 1: Crear `ExpenseCommand`**

```java
package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.Locale;

/**
 * {@code missionId} es opcional: un gasto puede ser general de la
 * empresa (ej. un dominio) o atribuido a una misión concreta. {@code
 * environment} sigue el mismo patrón que {@code MissionCommand} — default
 * {@code PRODUCTION} si se omite, quien carga un gasto de prueba debe
 * marcarlo {@code "TEST"} explícitamente para no contaminar el balance
 * real del ledger.
 */
public record ExpenseCommand(
        @Positive double amountUsd,
        @NotBlank String description,
        String missionId,
        String environment,
        @NotBlank String evidenceDescription,
        String evidenceSource,
        @NotBlank String evidenceSourceType,
        boolean evidenceVerified
) {
    public String environmentOrDefault() {
        return environment == null || environment.isBlank()
                ? "PRODUCTION"
                : environment.toUpperCase(Locale.ROOT);
    }
}
```

- [ ] **Step 2: Crear `ExpenseResponse`**

```java
package com.aicompany.core.model;

import java.time.Instant;

public record ExpenseResponse(
        String expenseId,
        double amountUsd,
        String description,
        String missionId,
        String environment,
        Instant createdAt
) {
}
```

- [ ] **Step 3: Escribir el test que falla — `ExpenseServiceTest`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.model.ExpenseCommand;
import com.aicompany.core.model.ExpenseResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExpenseServiceTest {

    private final EvidenceValidationGate evidenceGate = new EvidenceValidationGate();

    @Test
    void registersExpenseWithoutMissionWhenEvidenceIsValid() {
        var memory = mock(ExpenseMemoryService.class);
        when(memory.create(
                eq(12.0), eq("Dominio forjai.com"), isNull(), eq("PRODUCTION"),
                any(AgentResult.Evidence.class)
        )).thenReturn(new ExpenseResponse(
                "EXP-1", 12.0, "Dominio forjai.com", null, "PRODUCTION", Instant.now()
        ));

        var service = new ExpenseService(memory, evidenceGate);

        var command = new ExpenseCommand(
                12.0, "Dominio forjai.com", null, null,
                "Recibo de compra del dominio", "https://registrar.example.com/recibo/1",
                "INTERNAL", true
        );

        var response = service.registerExpense(command);

        assertEquals("EXP-1", response.expenseId());
        assertNull(response.missionId());
        verify(memory, never()).missionExists(any());
    }

    @Test
    void registersExpenseLinkedToAnExistingMission() {
        var memory = mock(ExpenseMemoryService.class);
        when(memory.missionExists("MISSION-1")).thenReturn(true);
        when(memory.create(
                eq(5.0), eq("Certificado SSL"), eq("MISSION-1"), eq("TEST"),
                any(AgentResult.Evidence.class)
        )).thenReturn(new ExpenseResponse(
                "EXP-2", 5.0, "Certificado SSL", "MISSION-1", "TEST", Instant.now()
        ));

        var service = new ExpenseService(memory, evidenceGate);

        var command = new ExpenseCommand(
                5.0, "Certificado SSL", "MISSION-1", "TEST",
                "Factura del proveedor", null, "INTERNAL", true
        );

        var response = service.registerExpense(command);

        assertEquals("MISSION-1", response.missionId());
        assertEquals("TEST", response.environment());
    }

    @Test
    void rejectsExpenseWhenMissionDoesNotExist() {
        var memory = mock(ExpenseMemoryService.class);
        when(memory.missionExists("MISSION-404")).thenReturn(false);

        var service = new ExpenseService(memory, evidenceGate);

        var command = new ExpenseCommand(
                10.0, "Gasto huérfano", "MISSION-404", null,
                "Sin comprobante real", null, "INTERNAL", false
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.registerExpense(command));

        verify(memory, never()).create(anyDouble(), any(), any(), any(), any());
    }

    @Test
    void rejectsExpenseWithSemanticallyInvalidEvidence() {
        var memory = mock(ExpenseMemoryService.class);

        var service = new ExpenseService(memory, evidenceGate);

        // verified=true con sourceType=NONE: contradicción semántica,
        // mismo gate que ya rechaza esto para Customer/Transaction.
        var command = new ExpenseCommand(
                10.0, "Gasto sin fuente real", null, null,
                "Confío en que fue así", null, "NONE", true
        );

        assertThrows(IllegalStateException.class,
                () -> service.registerExpense(command));

        verify(memory, never()).create(anyDouble(), any(), any(), any(), any());
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=ExpenseServiceTest`
Expected: FAIL (no compila — `ExpenseMemoryService`/`ExpenseService` todavía no existen)

- [ ] **Step 5: Crear `ExpenseMemoryService`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.model.ExpenseRecord;
import com.aicompany.core.model.ExpenseResponse;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistencia de gastos operativos reales — canal exclusivo del
 * fundador humano (empresa.md nivel 🔴: gastar dinero real requiere
 * aprobación humana), nunca de un agente. A diferencia de
 * {@code Customer}/{@code Transaction}, el nodo {@code Expense} no
 * guarda {@code missionId} como propiedad — la relación
 * {@code (:Mission)-[:HAS_EXPENSE]->(:Expense)} es la única fuente de
 * verdad de a qué misión (si alguna) pertenece un gasto.
 */
@Service
public class ExpenseMemoryService {

    private final Driver driver;

    public ExpenseMemoryService(Driver driver) {
        this.driver = driver;
    }

    public boolean missionExists(String missionId) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (m:Mission {id:$missionId}) RETURN count(m) AS total",
                            Map.of("missionId", missionId))
                    .single()
                    .get("total")
                    .asLong() > 0;
        }
    }

    public ExpenseResponse create(
            double amountUsd,
            String description,
            String missionId,
            String environment,
            AgentResult.Evidence evidence) {

        var expenseId = UUID.randomUUID().toString();
        var createdAt = Instant.now();

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                tx.run("MERGE (ex:Expense {id:$id}) " +
                                "SET ex.amountUsd=$amountUsd, ex.description=$description, " +
                                "ex.environment=$environment, ex.createdAt=$createdAt",
                        Map.of(
                                "id", expenseId,
                                "amountUsd", amountUsd,
                                "description", description,
                                "environment", environment,
                                "createdAt", createdAt.toString()
                        ));

                if (missionId != null) {
                    tx.run("MATCH (m:Mission {id:$missionId}), (ex:Expense {id:$id}) " +
                                    "MERGE (m)-[:HAS_EXPENSE]->(ex)",
                            Map.of("missionId", missionId, "id", expenseId));
                }

                tx.run("MATCH (ex:Expense {id:$id}) " +
                                "MERGE (e:Evidence {id:$evidenceId}) " +
                                "SET e.agentId='human', e.description=$description, " +
                                "e.source=$source, e.sourceType=$sourceType, " +
                                "e.verified=$verified, e.updatedAt=$createdAt " +
                                "MERGE (ex)-[:HAS_EVIDENCE]->(e)",
                        Map.of(
                                "id", expenseId,
                                "evidenceId", expenseId + "-EVIDENCE",
                                "description", evidence.description() == null ? "" : evidence.description(),
                                "source", evidence.source() == null ? "" : evidence.source(),
                                "sourceType", evidence.sourceType() == null ? "" : evidence.sourceType(),
                                "verified", evidence.verified(),
                                "createdAt", createdAt.toString()
                        ));

                return null;
            });
        }

        return new ExpenseResponse(expenseId, amountUsd, description, missionId, environment, createdAt);
    }

    /**
     * Usado por {@code LedgerService} (Task 2) para armar el ledger —
     * ver esa task para {@link ExpenseRecord}.
     */
    public List<ExpenseRecord> listAll() {
        try (var session = driver.session()) {
            var records = session.run(
                    "MATCH (ex:Expense) " +
                            "OPTIONAL MATCH (m:Mission)-[:HAS_EXPENSE]->(ex) " +
                            "RETURN ex.id AS id, ex.amountUsd AS amountUsd, " +
                            "ex.description AS description, m.id AS missionId, " +
                            "ex.environment AS environment, ex.createdAt AS createdAt"
            ).list();

            return records.stream()
                    .map(r -> new ExpenseRecord(
                            r.get("id").asString(),
                            r.get("amountUsd").asDouble(),
                            r.get("description").asString(),
                            r.get("missionId").isNull() ? null : r.get("missionId").asString(),
                            r.get("environment").asString(),
                            Instant.parse(r.get("createdAt").asString())
                    ))
                    .toList();
        }
    }
}
```

Nota: este archivo referencia `com.aicompany.core.model.ExpenseRecord`, que se crea recién en la Task 2 (la usa `listAll()`, que a su vez solo lo usará `LedgerService`) — Java no compilará este archivo solo hasta que exista `ExpenseRecord`. Como este plan se ejecuta task por task en el mismo módulo, seguí con la Task 2 antes de correr `mvn test` sobre todo el proyecto; el Step 6 de abajo compila y testea *solo* `ExpenseServiceTest`, que no toca `listAll()`.

- [ ] **Step 6: Crear `ExpenseService`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.model.ExpenseCommand;
import com.aicompany.core.model.ExpenseResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Igual criterio que {@code CustomerService}: "el agente afirma X" ≠ "la
 * empresa puede demostrar X" — ningún gasto se acepta sin evidencia que
 * pase {@link EvidenceValidationGate}. Solo el fundador humano llama a
 * este servicio (canal nivel 🔴 de empresa.md); no hay flujo que permita
 * a un agente registrar un gasto autónomamente.
 */
@Service
public class ExpenseService {

    private final ExpenseMemoryService memory;
    private final EvidenceValidationGate evidenceGate;

    public ExpenseService(ExpenseMemoryService memory, EvidenceValidationGate evidenceGate) {
        this.memory = memory;
        this.evidenceGate = evidenceGate;
    }

    public ExpenseResponse registerExpense(ExpenseCommand command) {

        if (command.missionId() != null && !command.missionId().isBlank()
                && !memory.missionExists(command.missionId())) {

            throw new IllegalArgumentException(
                    "No existe la misión " + command.missionId()
            );
        }

        var evidence = new AgentResult.Evidence(
                command.evidenceDescription(),
                command.evidenceSource(),
                command.evidenceSourceType(),
                command.evidenceVerified()
        );

        var validation = evidenceGate.validate(List.of(evidence));

        if (!validation.valid()) {
            throw new IllegalStateException(
                    "Evidencia inválida: " + String.join("; ", validation.errors())
            );
        }

        return memory.create(
                command.amountUsd(),
                command.description(),
                command.missionId(),
                command.environmentOrDefault(),
                evidence
        );
    }
}
```

- [ ] **Step 7: Agregar el constraint `expense_id` a `CompanyMemoryService`**

En `app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java`, en `initializeSchema()`, agregar esta línea junto a la de `transaction_id` (ambas dentro del mismo bloque de constraints ya existente):

```java
            session.run("CREATE CONSTRAINT expense_id IF NOT EXISTS FOR (ex:Expense) REQUIRE ex.id IS UNIQUE").consume();
```

- [ ] **Step 8: Correr `ExpenseServiceTest` y confirmar que pasa**

Run: `cd app && mvn test -Dtest=ExpenseServiceTest`
Expected: PASS (4/4) — nota: si el `mvn test` completo del módulo falla en este punto porque `ExpenseRecord`/`LedgerEntry`/etc. de la Task 2 todavía no existen, es esperado; seguí a la Task 2 antes de correr la suite completa.

- [ ] **Step 9: Commit**

```bash
cd /home/alex/Documentos/projectos/empresa
git add app/src/main/java/com/aicompany/core/model/ExpenseCommand.java \
        app/src/main/java/com/aicompany/core/model/ExpenseResponse.java \
        app/src/main/java/com/aicompany/core/service/ExpenseMemoryService.java \
        app/src/main/java/com/aicompany/core/service/ExpenseService.java \
        app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java \
        app/src/test/java/com/aicompany/core/service/ExpenseServiceTest.java
git commit -m "$(cat <<'EOF'
Agregar Expense: gastos operativos reales con evidencia validada

Mismo canal de confianza y mismo EvidenceValidationGate que
Customer/Transaction. missionId opcional (gasto general o atribuido a
una misión); environment sigue el patrón PRODUCTION/TEST de Mission.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013JtFpJQNiLdv72FtypWuaX
EOF
)"
```

---

### Task 2: Agregación del ledger (`LedgerService`)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/LedgerEntryType.java`
- Create: `app/src/main/java/com/aicompany/core/model/LedgerEntry.java`
- Create: `app/src/main/java/com/aicompany/core/model/LedgerResponse.java`
- Create: `app/src/main/java/com/aicompany/core/model/TransactionLedgerRow.java`
- Create: `app/src/main/java/com/aicompany/core/model/ExpenseRecord.java`
- Create: `app/src/main/java/com/aicompany/core/service/LedgerService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CustomerMemoryService.java` (agregar `listAllTransactions()`)
- Test: `app/src/test/java/com/aicompany/core/service/LedgerServiceTest.java`

**Interfaces:**
- Consumes: `ExpenseMemoryService.listAll() -> List<ExpenseRecord>` (Task 1); `AppProperties.seedCapitalUsd()` (ya existente).
- Produces: `CustomerMemoryService.listAllTransactions() -> List<TransactionLedgerRow>`; `LedgerService.buildLedger() -> LedgerResponse`. Estos dos nombres los usan la Task 3 (`LedgerController`) y la Task 4 (`ChatIntentRouter`).

- [ ] **Step 1: Crear `LedgerEntryType`**

```java
package com.aicompany.core.model;

public enum LedgerEntryType {
    SALE_REVENUE,
    SALE_COST,
    OPERATING_EXPENSE
}
```

- [ ] **Step 2: Crear `LedgerEntry`**

```java
package com.aicompany.core.model;

import java.time.Instant;

/**
 * {@code amountUsd} viene con signo (positivo para {@code SALE_REVENUE},
 * negativo para {@code SALE_COST}/{@code OPERATING_EXPENSE}) — sumar la
 * lista completa de una misma corrida da el balance. {@code
 * runningBalanceUsd} solo se calcula sobre líneas {@code PRODUCTION}
 * (acumulado en orden cronológico sobre esas líneas); en una línea
 * {@code TEST} viene {@code null} — se lista para visibilidad, pero no
 * afecta el balance real de la empresa.
 */
public record LedgerEntry(
        LedgerEntryType type,
        double amountUsd,
        String description,
        String missionId,
        String environment,
        Instant occurredAt,
        Double runningBalanceUsd
) {
}
```

- [ ] **Step 3: Crear `LedgerResponse`**

```java
package com.aicompany.core.model;

import java.util.List;

public record LedgerResponse(
        double seedCapitalUsd,
        double balanceUsd,
        List<LedgerEntry> entries
) {
}
```

- [ ] **Step 4: Crear `TransactionLedgerRow`**

```java
package com.aicompany.core.model;

import java.time.Instant;

/**
 * Fila cruda de una {@code Transaction} real, leída para armar el
 * ledger — {@code environment} viene de la {@code Mission} a la que
 * pertenece la transacción (una {@code Transaction} no tiene su propio
 * campo de entorno). Ver {@code CustomerMemoryService.listAllTransactions()}.
 */
public record TransactionLedgerRow(
        String missionId,
        String environment,
        String description,
        double revenueUsd,
        double costUsd,
        Instant recordedAt
) {
}
```

- [ ] **Step 5: Crear `ExpenseRecord`**

```java
package com.aicompany.core.model;

import java.time.Instant;

/**
 * Fila cruda de un {@code Expense} real, leída para armar el ledger —
 * ver {@code ExpenseMemoryService.listAll()}.
 */
public record ExpenseRecord(
        String expenseId,
        double amountUsd,
        String description,
        String missionId,
        String environment,
        Instant createdAt
) {
}
```

- [ ] **Step 6: Escribir el test que falla — `LedgerServiceTest`**

```java
package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.ExpenseRecord;
import com.aicompany.core.model.LedgerEntryType;
import com.aicompany.core.model.TransactionLedgerRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    private final AppProperties appProperties = new AppProperties("Forjai", 50.0, 60);

    @Test
    void splitsEachTransactionIntoRevenueAndCostLinesAndAccumulatesBalance() {
        var customerMemory = mock(CustomerMemoryService.class);
        var expenseMemory = mock(ExpenseMemoryService.class);

        when(customerMemory.listAllTransactions()).thenReturn(List.of(
                new TransactionLedgerRow(
                        "MISSION-1", "PRODUCTION", "Venta de asesoría",
                        100.0, 40.0, Instant.parse("2026-09-10T00:00:00Z")
                )
        ));
        when(expenseMemory.listAll()).thenReturn(List.of());

        var service = new LedgerService(customerMemory, expenseMemory, appProperties);

        var ledger = service.buildLedger();

        assertEquals(2, ledger.entries().size());
        assertEquals(LedgerEntryType.SALE_REVENUE, ledger.entries().get(0).type());
        assertEquals(100.0, ledger.entries().get(0).amountUsd(), 0.0001);
        assertEquals(150.0, ledger.entries().get(0).runningBalanceUsd(), 0.0001);

        assertEquals(LedgerEntryType.SALE_COST, ledger.entries().get(1).type());
        assertEquals(-40.0, ledger.entries().get(1).amountUsd(), 0.0001);
        assertEquals(110.0, ledger.entries().get(1).runningBalanceUsd(), 0.0001);

        assertEquals(110.0, ledger.balanceUsd(), 0.0001);
        assertEquals(50.0, ledger.seedCapitalUsd(), 0.0001);
    }

    @Test
    void includesOperatingExpensesAsNegativeLines() {
        var customerMemory = mock(CustomerMemoryService.class);
        var expenseMemory = mock(ExpenseMemoryService.class);

        when(customerMemory.listAllTransactions()).thenReturn(List.of());
        when(expenseMemory.listAll()).thenReturn(List.of(
                new ExpenseRecord(
                        "EXP-1", 12.0, "Dominio forjai.com", null, "PRODUCTION",
                        Instant.parse("2026-09-11T00:00:00Z")
                )
        ));

        var service = new LedgerService(customerMemory, expenseMemory, appProperties);

        var ledger = service.buildLedger();

        assertEquals(1, ledger.entries().size());
        assertEquals(LedgerEntryType.OPERATING_EXPENSE, ledger.entries().get(0).type());
        assertEquals(-12.0, ledger.entries().get(0).amountUsd(), 0.0001);
        assertEquals(38.0, ledger.balanceUsd(), 0.0001);
    }

    @Test
    void excludesTestEnvironmentLinesFromTheRealBalanceButStillListsThem() {
        var customerMemory = mock(CustomerMemoryService.class);
        var expenseMemory = mock(ExpenseMemoryService.class);

        when(customerMemory.listAllTransactions()).thenReturn(List.of(
                new TransactionLedgerRow(
                        "MISSION-TEST", "TEST", "Venta de prueba",
                        1000.0, 0.0, Instant.parse("2026-09-09T00:00:00Z")
                )
        ));
        when(expenseMemory.listAll()).thenReturn(List.of());

        var service = new LedgerService(customerMemory, expenseMemory, appProperties);

        var ledger = service.buildLedger();

        assertEquals(1, ledger.entries().size());
        assertEquals("TEST", ledger.entries().get(0).environment());
        assertNull(ledger.entries().get(0).runningBalanceUsd());
        // El balance real no incluye la venta de US$1000 de TEST -- sigue
        // siendo el capital semilla, sin movimientos PRODUCTION.
        assertEquals(50.0, ledger.balanceUsd(), 0.0001);
    }

    @Test
    void ordersEntriesChronologicallyAcrossTransactionsAndExpenses() {
        var customerMemory = mock(CustomerMemoryService.class);
        var expenseMemory = mock(ExpenseMemoryService.class);

        when(customerMemory.listAllTransactions()).thenReturn(List.of(
                new TransactionLedgerRow(
                        "MISSION-1", "PRODUCTION", "Venta reciente",
                        20.0, 0.0, Instant.parse("2026-09-15T00:00:00Z")
                )
        ));
        when(expenseMemory.listAll()).thenReturn(List.of(
                new ExpenseRecord(
                        "EXP-1", 5.0, "Gasto anterior", null, "PRODUCTION",
                        Instant.parse("2026-09-01T00:00:00Z")
                )
        ));

        var service = new LedgerService(customerMemory, expenseMemory, appProperties);

        var ledger = service.buildLedger();

        assertEquals("Gasto anterior", ledger.entries().get(0).description());
        assertEquals("Venta reciente", ledger.entries().get(1).description());
    }
}
```

- [ ] **Step 7: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=LedgerServiceTest`
Expected: FAIL (no compila — `LedgerService` no existe, `CustomerMemoryService.listAllTransactions()` no existe)

- [ ] **Step 8: Agregar `listAllTransactions()` a `CustomerMemoryService`**

Agregar este método a `app/src/main/java/com/aicompany/core/service/CustomerMemoryService.java` (después de `companyWideTotalRevenueAndCost()`), y agregar `import com.aicompany.core.model.TransactionLedgerRow;` al inicio del archivo:

```java
    /**
     * Filas crudas de todas las {@code Transaction} reales, con el
     * {@code environment} de su {@code Mission} -- usado por
     * {@code LedgerService} para armar el ledger completo (a diferencia
     * de {@link #companyWideTotalRevenueAndCost}, que solo agrega
     * totales y no distingue PRODUCTION de TEST).
     */
    public List<TransactionLedgerRow> listAllTransactions() {
        try (var session = driver.session()) {
            var records = session.run(
                    "MATCH (m:Mission)-[:HAS_TRANSACTION]->(t:Transaction) " +
                            "RETURN t.missionId AS missionId, " +
                            "coalesce(m.environment, 'TEST') AS environment, " +
                            "t.description AS description, t.revenueUsd AS revenueUsd, " +
                            "t.costUsd AS costUsd, t.recordedAt AS recordedAt"
            ).list();

            return records.stream()
                    .map(r -> new TransactionLedgerRow(
                            r.get("missionId").asString(),
                            r.get("environment").asString(),
                            r.get("description").asString(),
                            r.get("revenueUsd").asDouble(),
                            r.get("costUsd").asDouble(),
                            Instant.parse(r.get("recordedAt").asString())
                    ))
                    .toList();
        }
    }
```

(`java.util.List` ya está importado en este archivo; `Instant` también.)

- [ ] **Step 9: Crear `LedgerService`**

```java
package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.LedgerEntry;
import com.aicompany.core.model.LedgerEntryType;
import com.aicompany.core.model.LedgerResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * El ledger no se persiste -- se arma en cada request combinando {@code
 * Transaction} (real, ya existente, leída vía {@code
 * CustomerMemoryService.listAllTransactions}) y {@code Expense} (nuevo,
 * {@code ExpenseMemoryService.listAll}), igual patrón que {@code
 * ActivityMemoryService.recent()} (una vista calculada sobre entidades
 * existentes, sin duplicar datos en un nodo "LedgerEntry" propio).
 *
 * <p>Cada {@code Transaction} se parte en hasta dos líneas
 * ({@code SALE_REVENUE}/{@code SALE_COST}) para que sea contabilidad
 * real (bruta), no solo el neto que ya expone {@code GET /net-profit}.
 * El balance corriente ({@code runningBalanceUsd}) solo avanza sobre
 * líneas {@code PRODUCTION} -- una línea {@code TEST} se lista para
 * visibilidad pero no afecta el balance real de la empresa (mismo
 * criterio que {@code MISSIONS_NEEDING_ATTENTION}/{@code FAILED_MISSIONS}
 * en {@code ChatIntentRouter}).
 */
@Service
public class LedgerService {

    private final CustomerMemoryService customerMemory;
    private final ExpenseMemoryService expenseMemory;
    private final AppProperties appProperties;

    public LedgerService(
            CustomerMemoryService customerMemory,
            ExpenseMemoryService expenseMemory,
            AppProperties appProperties) {

        this.customerMemory = customerMemory;
        this.expenseMemory = expenseMemory;
        this.appProperties = appProperties;
    }

    public LedgerResponse buildLedger() {

        var rawEntries = new ArrayList<RawEntry>();

        for (var tx : customerMemory.listAllTransactions()) {

            if (tx.revenueUsd() > 0) {
                rawEntries.add(new RawEntry(
                        LedgerEntryType.SALE_REVENUE, tx.revenueUsd(), tx.description(),
                        tx.missionId(), tx.environment(), tx.recordedAt()
                ));
            }

            if (tx.costUsd() > 0) {
                rawEntries.add(new RawEntry(
                        LedgerEntryType.SALE_COST, -tx.costUsd(), tx.description(),
                        tx.missionId(), tx.environment(), tx.recordedAt()
                ));
            }
        }

        for (var expense : expenseMemory.listAll()) {
            rawEntries.add(new RawEntry(
                    LedgerEntryType.OPERATING_EXPENSE, -expense.amountUsd(), expense.description(),
                    expense.missionId(), expense.environment(), expense.createdAt()
            ));
        }

        rawEntries.sort(Comparator.comparing(RawEntry::occurredAt));

        var entries = new ArrayList<LedgerEntry>();
        var runningBalance = appProperties.seedCapitalUsd();

        for (var raw : rawEntries) {

            Double runningBalanceUsd = null;

            if ("PRODUCTION".equals(raw.environment())) {
                runningBalance += raw.amountUsd();
                runningBalanceUsd = runningBalance;
            }

            entries.add(new LedgerEntry(
                    raw.type(), raw.amountUsd(), raw.description(),
                    raw.missionId(), raw.environment(), raw.occurredAt(), runningBalanceUsd
            ));
        }

        return new LedgerResponse(appProperties.seedCapitalUsd(), runningBalance, entries);
    }

    private record RawEntry(
            LedgerEntryType type,
            double amountUsd,
            String description,
            String missionId,
            String environment,
            Instant occurredAt
    ) {
    }
}
```

- [ ] **Step 10: Correr `LedgerServiceTest` y confirmar que pasa**

Run: `cd app && mvn test -Dtest=LedgerServiceTest`
Expected: PASS (4/4)

- [ ] **Step 11: Correr `ExpenseServiceTest` de nuevo (ahora que `ExpenseRecord` existe) y toda la suite**

Run: `cd app && mvn test`
Expected: PASS — todos los tests existentes más los nuevos de las Tasks 1 y 2 (ningún test viejo debería romperse: no se tocó ninguna firma pública usada fuera de estos archivos todavía).

- [ ] **Step 12: Commit**

```bash
cd /home/alex/Documentos/projectos/empresa
git add app/src/main/java/com/aicompany/core/model/LedgerEntryType.java \
        app/src/main/java/com/aicompany/core/model/LedgerEntry.java \
        app/src/main/java/com/aicompany/core/model/LedgerResponse.java \
        app/src/main/java/com/aicompany/core/model/TransactionLedgerRow.java \
        app/src/main/java/com/aicompany/core/model/ExpenseRecord.java \
        app/src/main/java/com/aicompany/core/service/LedgerService.java \
        app/src/main/java/com/aicompany/core/service/CustomerMemoryService.java \
        app/src/test/java/com/aicompany/core/service/LedgerServiceTest.java
git commit -m "$(cat <<'EOF'
Agregar LedgerService: balance real combinando Transaction + Expense

Vista calculada, sin duplicar Transaction en un nodo nuevo -- cada
venta se parte en línea de ingreso y línea de costo, el balance
corriente solo avanza sobre líneas PRODUCTION.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013JtFpJQNiLdv72FtypWuaX
EOF
)"
```

---

### Task 3: API REST del ledger (`LedgerController`)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/controller/LedgerController.java`
- Test: `app/src/test/java/com/aicompany/core/controller/LedgerControllerTest.java`

**Interfaces:**
- Consumes: `ExpenseService.registerExpense(ExpenseCommand) -> ExpenseResponse` (Task 1); `LedgerService.buildLedger() -> LedgerResponse` (Task 2).
- Produces: `POST /api/company/ledger/expenses`, `GET /api/company/ledger` — usados por el frontend en la Task 5.

- [ ] **Step 1: Escribir el test que falla — `LedgerControllerTest`**

```java
package com.aicompany.core.controller;

import com.aicompany.core.model.ExpenseCommand;
import com.aicompany.core.model.ExpenseResponse;
import com.aicompany.core.model.LedgerResponse;
import com.aicompany.core.service.ExpenseService;
import com.aicompany.core.service.LedgerService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LedgerControllerTest {

    private final ExpenseService expenseService = mock(ExpenseService.class);
    private final LedgerService ledgerService = mock(LedgerService.class);

    private final LedgerController controller =
            new LedgerController(expenseService, ledgerService);

    @Test
    void registerExpenseDelegatesToExpenseService() {
        var command = new ExpenseCommand(
                12.0, "Dominio forjai.com", null, null,
                "Recibo de compra", null, "INTERNAL", true
        );
        var expected = new ExpenseResponse(
                "EXP-1", 12.0, "Dominio forjai.com", null, "PRODUCTION", Instant.now()
        );
        when(expenseService.registerExpense(command)).thenReturn(expected);

        var response = controller.registerExpense(command);

        assertEquals(expected, response.getBody());
    }

    @Test
    void ledgerDelegatesToLedgerService() {
        var expected = new LedgerResponse(50.0, 38.0, List.of());
        when(ledgerService.buildLedger()).thenReturn(expected);

        var response = controller.ledger();

        assertEquals(expected, response.getBody());
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=LedgerControllerTest`
Expected: FAIL (`LedgerController` no existe)

- [ ] **Step 3: Crear `LedgerController`**

```java
package com.aicompany.core.controller;

import com.aicompany.core.model.ExpenseCommand;
import com.aicompany.core.model.ExpenseResponse;
import com.aicompany.core.model.LedgerResponse;
import com.aicompany.core.service.ExpenseService;
import com.aicompany.core.service.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Un solo endpoint de escritura (no anidado bajo
 * {@code /missions/{missionId}/...}) porque {@code missionId} es
 * opcional en {@code ExpenseCommand} -- evita duplicar la ruta para
 * gasto-de-misión vs. gasto-general.
 */
@RestController
@RequestMapping("/api/company/ledger")
public class LedgerController {

    private final ExpenseService expenseService;
    private final LedgerService ledgerService;

    public LedgerController(ExpenseService expenseService, LedgerService ledgerService) {
        this.expenseService = expenseService;
        this.ledgerService = ledgerService;
    }

    @PostMapping("/expenses")
    public ResponseEntity<ExpenseResponse> registerExpense(@Valid @RequestBody ExpenseCommand command) {
        return ResponseEntity.ok(expenseService.registerExpense(command));
    }

    @GetMapping
    public ResponseEntity<LedgerResponse> ledger() {
        return ResponseEntity.ok(ledgerService.buildLedger());
    }
}
```

- [ ] **Step 4: Correr el test y confirmar que pasa**

Run: `cd app && mvn test -Dtest=LedgerControllerTest`
Expected: PASS (2/2)

- [ ] **Step 5: Commit**

```bash
cd /home/alex/Documentos/projectos/empresa
git add app/src/main/java/com/aicompany/core/controller/LedgerController.java \
        app/src/test/java/com/aicompany/core/controller/LedgerControllerTest.java
git commit -m "$(cat <<'EOF'
Exponer el ledger vía REST: POST /ledger/expenses, GET /ledger

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013JtFpJQNiLdv72FtypWuaX
EOF
)"
```

---

### Task 4: Integración con el chat (`ChatIntentRouter` + `CeoService`)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `LedgerService.buildLedger() -> LedgerResponse` (Task 2).
- Produces: nada que otra task consuma — es la integración final de cara al usuario (chat) para este ledger.

- [ ] **Step 1: Actualizar el sitio de construcción de `ChatIntentRouter` en el test (para que compile con el nuevo parámetro) y agregar los casos nuevos**

En `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`:

1. Agregar el campo mock, justo después de `appProperties`:

```java
    private final LedgerService ledgerService = mock(LedgerService.class);
```

2. Agregar `ledgerService` como último argumento en la construcción de `router`:

```java
    private final ChatIntentRouter router = new ChatIntentRouter(
            missionService, ceoService, missionMemory, opportunityMemory, customerMemory,
            companyMemory, conversationMemory, appProperties, ledgerService
    );
```

3. Agregar el import al inicio del archivo:

```java
import com.aicompany.core.model.LedgerEntry;
import com.aicompany.core.model.LedgerEntryType;
import com.aicompany.core.model.LedgerResponse;
```

4. Agregar un test nuevo, después de `routesCompanyProfitQueryWithDeterministicAggregation`:

```java
    @Test
    void routesLedgerQueryWithDeterministicFormatting() {
        var entry = new LedgerEntry(
                LedgerEntryType.OPERATING_EXPENSE, -12.0, "Dominio forjai.com",
                null, "PRODUCTION", Instant.parse("2026-09-10T00:00:00Z"), 38.0
        );
        when(ledgerService.buildLedger()).thenReturn(new LedgerResponse(50.0, 38.0, List.of(entry)));

        var response = router.route("¿cómo va el ledger de la empresa?");

        assertTrue(response.contains("US$38.00"));
        assertTrue(response.contains("Dominio forjai.com"));
        verifyNoInteractions(ceoService);
    }
```

5. Actualizar `routesCompanyStatusQueryToADeterministicAggregateSnapshot`: agregar, junto a los demás `when(...)` de ese test, el mock del ledger, y una assertion nueva:

```java
        when(ledgerService.buildLedger()).thenReturn(new LedgerResponse(50.0, 88.0, List.of()));
```

```java
        assertTrue(response.contains("US$88.00"));
```

6. Actualizar `passesCompanyMemoryQueryCallbackThatResolvesAllKnownTopics`: agregar, junto a los demás `when(...)`, y una assertion nueva:

```java
        when(ledgerService.buildLedger()).thenReturn(new LedgerResponse(50.0, 50.0, List.of()));
```

```java
        assertTrue(companyMemoryQuery.apply("LEDGER").contains("Balance actual"));
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: FAIL (no compila — `ChatIntentRouter` todavía no tiene 9 parámetros ni conoce `LEDGER`)

- [ ] **Step 3: Modificar `ChatIntentRouter` — constructor y campo**

En `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`, agregar el campo (después de `appProperties`):

```java
    private final LedgerService ledgerService;
```

Y agregar el parámetro al constructor (último, después de `appProperties`):

```java
    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            CompanyMemoryService companyMemory,
            ConversationMemoryService conversationMemory,
            AppProperties appProperties,
            LedgerService ledgerService) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.companyMemory = companyMemory;
        this.conversationMemory = conversationMemory;
        this.appProperties = appProperties;
        this.ledgerService = ledgerService;
    }
```

- [ ] **Step 4: Agregar `LEDGER` al enum `QueryIntent` y a `detectQuery`**

```java
    private enum QueryIntent {
        AGENT_STATUS,
        MISSIONS_NEEDING_ATTENTION,
        FAILED_MISSIONS,
        TEST_MISSIONS,
        OPPORTUNITIES,
        COMPANY_PROFIT,
        LEDGER,
        COMPANY_STATUS
    }
```

En `detectQuery`, agregar este bloque **después** del `if` de `COMPANY_PROFIT` y **antes** del `if` de `COMPANY_STATUS` (nota: deliberadamente NO se usan las palabras "gasto"/"gastado" — esas ya están tomadas por `COMPANY_PROFIT` un poco más arriba en este mismo método; usar esas palabras acá les robaría la ruta a preguntas de rentabilidad ya existentes):

```java
        if (normalized.contains("ledger")
                || normalized.contains("libro contable")
                || normalized.contains("movimientos")
                || normalized.contains("balance")) {
            return QueryIntent.LEDGER;
        }

```

- [ ] **Step 5: Agregar el caso `LEDGER` a `answerMemoryTopic` y el formatter**

En el `switch` de `answerMemoryTopic`:

```java
            case "LEDGER" -> formatLedger(ledgerService.buildLedger());
```

Y el nuevo método privado, después de `formatCompanyProfit` (o donde esté ese método — buscarlo por nombre si no está inmediatamente después de `formatCompanyStatus`):

```java
    private static final int LEDGER_RECENT_LIMIT = 10;

    private String formatLedger(com.aicompany.core.model.LedgerResponse ledger) {

        var productionEntries = ledger.entries().stream()
                .filter(e -> "PRODUCTION".equals(e.environment()))
                .toList();

        if (productionEntries.isEmpty()) {
            return String.format(
                    Locale.ROOT,
                    "Balance actual: US$%.2f (capital semilla US$%.2f). Sin movimientos reales registrados todavía.",
                    ledger.balanceUsd(), ledger.seedCapitalUsd()
            );
        }

        var recent = productionEntries.subList(
                Math.max(0, productionEntries.size() - LEDGER_RECENT_LIMIT),
                productionEntries.size()
        );

        var lines = recent.stream()
                .map(e -> String.format(
                        Locale.ROOT, "%s US$%.2f: %s",
                        e.amountUsd() >= 0 ? "+" : "-", Math.abs(e.amountUsd()), e.description()
                ))
                .collect(Collectors.joining("; "));

        return String.format(
                Locale.ROOT,
                "Balance actual: US$%.2f (capital semilla US$%.2f). Últimos movimientos: %s.",
                ledger.balanceUsd(), ledger.seedCapitalUsd(), lines
        );
    }
```

- [ ] **Step 6: Agregar la cláusula de balance a `formatCompanyStatus`**

En `formatCompanyStatus()`, justo antes del `return String.format(...)`, agregar:

```java
        var ledgerBalance = ledgerService.buildLedger().balanceUsd();
```

Y modificar el `String.format` final agregando una cláusula al string y un argumento al final:

```java
        return String.format(
                Locale.ROOT,
                "Estado actual de Forjai: capital disponible US$%.2f. "
                        + "Agentes: %d trabajando, %d inactivo(s). "
                        + "Misiones (producción): %d activa(s), %d esperando tu aprobación, %d fallida(s). "
                        + "Oportunidades registradas: %d. Prospectos (leads): %d. Clientes reales: %d. "
                        + "Ingresos: US$%.2f. Beneficio neto: US$%.2f. "
                        + "Balance real (ledger): US$%.2f.",
                appProperties.seedCapitalUsd(), working, idle,
                active, awaitingInvestor, failed,
                opportunities, prospects, customers,
                revenue, netProfit,
                ledgerBalance
        );
```

- [ ] **Step 7: Agregar `LEDGER` al enum `topic` de `CeoService`**

En `app/src/main/java/com/aicompany/core/service/CeoService.java`, dentro de `COMPANY_MEMORY_TOOLS`, agregar `"LEDGER"` a la lista del `enum` (después de `"COMPANY_PROFIT"`):

```java
                                                    "enum", List.of(
                                                            "AGENT_STATUS",
                                                            "MISSIONS_NEEDING_ATTENTION",
                                                            "FAILED_MISSIONS",
                                                            "TEST_MISSIONS",
                                                            "LAST_MENTIONED",
                                                            "OPPORTUNITIES",
                                                            "COMPANY_PROFIT",
                                                            "LEDGER",
                                                            "COMPANY_STATUS"
                                                    ),
```

Y agregar la descripción del topic nuevo, en la concatenación de `"description"`, después del bloque de `COMPANY_PROFIT` y antes del de `COMPANY_STATUS`:

```java
                                                            + "LEDGER: balance "
                                                            + "real y últimos "
                                                            + "movimientos "
                                                            + "financieros "
                                                            + "(ingresos, costos "
                                                            + "de venta, gastos "
                                                            + "operativos). "
```

- [ ] **Step 8: Correr `ChatIntentRouterTest` y confirmar que pasa**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: PASS (todos los casos, incluidos los 3 nuevos/actualizados)

- [ ] **Step 9: Correr toda la suite**

Run: `cd app && mvn test`
Expected: PASS — confirma que no se rompió ningún test de `CeoServiceToolFormatGuardTest`/`CeoServiceChatHistoryTest` (no dependen de la lista de topics) ni de ningún otro archivo.

- [ ] **Step 10: Commit**

```bash
cd /home/alex/Documentos/projectos/empresa
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/main/java/com/aicompany/core/service/CeoService.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "$(cat <<'EOF'
Exponer el ledger en el chat: intent LEDGER + balance en COMPANY_STATUS

Mismo criterio de siempre: el balance y los movimientos se calculan
100% en Java (LedgerService), el chat solo redacta sobre el resultado
real -- nunca deja que el modelo invente una cifra.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013JtFpJQNiLdv72FtypWuaX
EOF
)"
```

---

### Task 5: Command Center — pantalla Ledger

**Files:**
- Modify: `app/frontend/src/api/types.ts`
- Modify: `app/frontend/src/api/client.ts`
- Create: `app/frontend/src/pages/LedgerPage.tsx`
- Modify: `app/frontend/src/App.tsx`
- Modify: `app/frontend/src/components/Layout.tsx`
- Modify: `app/src/main/java/com/aicompany/core/controller/SpaController.java`

**Interfaces:**
- Consumes: `GET /api/company/ledger` → `LedgerResponse`; `POST /api/company/ledger/expenses` → `ExpenseResponse` (Task 3).
- Produces: nada — última pieza de cara al usuario.

- [ ] **Step 1: Agregar los tipos nuevos a `api/types.ts`**

Agregar al final del archivo:

```typescript
export type LedgerEntryType = 'SALE_REVENUE' | 'SALE_COST' | 'OPERATING_EXPENSE'

// amountUsd viene con signo (positivo=ingreso, negativo=costo/gasto);
// runningBalanceUsd es null en líneas TEST -- no afectan el balance real.
export interface LedgerEntry {
  type: LedgerEntryType
  amountUsd: number
  description: string
  missionId: string | null
  environment: string
  occurredAt: string
  runningBalanceUsd: number | null
}

export interface LedgerResponse {
  seedCapitalUsd: number
  balanceUsd: number
  entries: LedgerEntry[]
}

export interface ExpenseCommand {
  amountUsd: number
  description: string
  missionId: string | null
  environment: string | null
  evidenceDescription: string
  evidenceSource: string
  evidenceSourceType: string
  evidenceVerified: boolean
}

export interface ExpenseResponse {
  expenseId: string
  amountUsd: number
  description: string
  missionId: string | null
  environment: string
  createdAt: string
}
```

- [ ] **Step 2: Agregar los métodos nuevos a `api/client.ts`**

Modificar el import del inicio del archivo para incluir los tipos nuevos:

```typescript
import type {
  ActivityItem,
  AgentStatusResponse,
  ChatResponse,
  DecisionCommand,
  DecisionResponse,
  ExpenseCommand,
  ExpenseResponse,
  LedgerResponse,
  MissionResponse,
  MissionStatusResponse,
  SettingsCommand,
  SettingsResponse,
} from './types'
```

Y agregar al objeto `api`, antes del cierre `}`:

```typescript
  ledger: () => request<LedgerResponse>('/api/company/ledger'),

  createExpense: (command: ExpenseCommand) =>
    request<ExpenseResponse>('/api/company/ledger/expenses', {
      method: 'POST',
      body: JSON.stringify(command),
    }),
```

- [ ] **Step 3: Crear `LedgerPage.tsx`**

```tsx
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'

export default function LedgerPage() {
  const queryClient = useQueryClient()
  const [amountUsd, setAmountUsd] = useState('')
  const [description, setDescription] = useState('')
  const [missionId, setMissionId] = useState('')
  const [environment, setEnvironment] = useState('PRODUCTION')
  const [evidenceDescription, setEvidenceDescription] = useState('')
  const [evidenceSource, setEvidenceSource] = useState('')
  const [evidenceSourceType, setEvidenceSourceType] = useState('INTERNAL')
  const [evidenceVerified, setEvidenceVerified] = useState(false)
  const [feedback, setFeedback] = useState<string | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: ['ledger'],
    queryFn: api.ledger,
    refetchInterval: 10_000,
  })

  const mutation = useMutation({
    mutationFn: () =>
      api.createExpense({
        amountUsd: parseFloat(amountUsd),
        description,
        missionId: missionId.trim() ? missionId.trim() : null,
        environment,
        evidenceDescription,
        evidenceSource,
        evidenceSourceType,
        evidenceVerified,
      }),
    onSuccess: () => {
      setFeedback('Gasto registrado.')
      setAmountUsd('')
      setDescription('')
      setMissionId('')
      setEvidenceDescription('')
      setEvidenceSource('')
      queryClient.invalidateQueries({ queryKey: ['ledger'] })
    },
    onError: () =>
      setFeedback('No se pudo registrar el gasto (¿evidencia inválida o misión inexistente?).'),
  })

  if (isLoading) return <p>Cargando ledger...</p>
  if (error) return <p className="error">No se pudo cargar el ledger.</p>

  return (
    <div>
      <h1>Ledger</h1>
      <p className="hint">
        Capital semilla: US${data?.seedCapitalUsd.toFixed(2)} — Balance actual:{' '}
        <strong>US${data?.balanceUsd.toFixed(2)}</strong>
      </p>

      <table className="data-table">
        <thead>
          <tr>
            <th>Tipo</th>
            <th>Monto</th>
            <th>Descripción</th>
            <th>Misión</th>
            <th>Entorno</th>
            <th>Balance</th>
            <th>Fecha</th>
          </tr>
        </thead>
        <tbody>
          {data?.entries.map((entry, index) => (
            <tr key={index}>
              <td>{entry.type}</td>
              <td>
                {entry.amountUsd >= 0 ? '+' : '-'}US${Math.abs(entry.amountUsd).toFixed(2)}
              </td>
              <td>{entry.description}</td>
              <td>{entry.missionId ?? '—'}</td>
              <td>{entry.environment === 'PRODUCTION' ? '🏢 PRODUCTION' : '🧪 TEST'}</td>
              <td>
                {entry.runningBalanceUsd != null ? `US$${entry.runningBalanceUsd.toFixed(2)}` : '—'}
              </td>
              <td>{new Date(entry.occurredAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>Registrar gasto operativo</h2>
      <form
        className="decision-form"
        onSubmit={(e) => {
          e.preventDefault()
          setFeedback(null)
          mutation.mutate()
        }}
      >
        <label>
          Monto (USD)
          <input
            type="number"
            step="0.01"
            min="0.01"
            required
            value={amountUsd}
            onChange={(e) => setAmountUsd(e.target.value)}
          />
        </label>
        <label>
          Descripción
          <input
            type="text"
            required
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </label>
        <label>
          Misión (opcional)
          <input
            type="text"
            value={missionId}
            onChange={(e) => setMissionId(e.target.value)}
            placeholder="MISSION-123"
          />
        </label>
        <label>
          Entorno
          <select value={environment} onChange={(e) => setEnvironment(e.target.value)}>
            <option value="PRODUCTION">PRODUCTION</option>
            <option value="TEST">TEST</option>
          </select>
        </label>
        <label>
          Evidencia: descripción
          <input
            type="text"
            required
            value={evidenceDescription}
            onChange={(e) => setEvidenceDescription(e.target.value)}
          />
        </label>
        <label>
          Evidencia: fuente (URL o referencia)
          <input
            type="text"
            value={evidenceSource}
            onChange={(e) => setEvidenceSource(e.target.value)}
          />
        </label>
        <label>
          Evidencia: tipo
          <select value={evidenceSourceType} onChange={(e) => setEvidenceSourceType(e.target.value)}>
            <option value="INTERNAL">INTERNAL</option>
            <option value="WEB">WEB</option>
            <option value="CUSTOMER">CUSTOMER</option>
            <option value="TRANSACTION">TRANSACTION</option>
            <option value="NONE">NONE</option>
          </select>
        </label>
        <label>
          <input
            type="checkbox"
            checked={evidenceVerified}
            onChange={(e) => setEvidenceVerified(e.target.checked)}
          />
          Evidencia verificada
        </label>

        <button
          type="submit"
          disabled={mutation.isPending || !amountUsd || !description.trim() || !evidenceDescription.trim()}
        >
          {mutation.isPending ? 'Guardando...' : 'Registrar gasto'}
        </button>
      </form>
      {feedback && <p className="feedback">{feedback}</p>}
    </div>
  )
}
```

- [ ] **Step 4: Agregar la ruta a `App.tsx`**

Agregar el import junto a los demás:

```typescript
import LedgerPage from './pages/LedgerPage'
```

Y la ruta, después de `<Route path="settings" element={<SettingsPage />} />`:

```tsx
            <Route path="ledger" element={<LedgerPage />} />
```

- [ ] **Step 5: Agregar el link de navegación a `Layout.tsx`**

Después de `<NavLink to="/activity">Activity</NavLink>`:

```tsx
          <NavLink to="/ledger">Ledger</NavLink>
```

- [ ] **Step 6: Agregar `/ledger` a `SpaController`**

En `app/src/main/java/com/aicompany/core/controller/SpaController.java`, agregar `"/ledger"` a la lista de `@RequestMapping`:

```java
    @RequestMapping(value = {
            "/",
            "/chat",
            "/agents",
            "/missions",
            "/missions/{missionId}",
            "/activity",
            "/settings",
            "/ledger"
    })
```

- [ ] **Step 7: Compilar el frontend y confirmar que no hay errores de tipos**

Run: `cd app/frontend && npm run build`
Expected: build exitoso, sin errores de TypeScript.

- [ ] **Step 8: Correr toda la suite de backend una vez más**

Run: `cd app && mvn test`
Expected: PASS — cambio de `SpaController` es solo backend, confirmá que sigue compilando y los tests de rutas (si existen) no se rompen.

- [ ] **Step 9: Verificación manual (no automatizable en este plan)**

Levantar el backend real (`mvn spring-boot:run` desde `app/`, con Neo4j accesible) y el frontend (`npm run dev` desde `app/frontend/`, apuntando al backend real): confirmar que `/ledger` carga, muestra el balance, y que cargar un gasto de prueba (`environment: TEST`) lo refleja en la tabla sin mover el balance real. Esto queda para cuando el usuario pruebe la feature en vivo — no es parte de este plan de implementación automatizable.

- [ ] **Step 10: Commit**

```bash
cd /home/alex/Documentos/projectos/empresa
git add app/frontend/src/api/types.ts \
        app/frontend/src/api/client.ts \
        app/frontend/src/pages/LedgerPage.tsx \
        app/frontend/src/App.tsx \
        app/frontend/src/components/Layout.tsx \
        app/src/main/java/com/aicompany/core/controller/SpaController.java
git commit -m "$(cat <<'EOF'
Agregar pantalla Ledger al Command Center web

Tabla de movimientos (con balance corriente) + formulario para
registrar un gasto operativo, mismo patrón que SettingsPage.tsx.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013JtFpJQNiLdv72FtypWuaX
EOF
)"
```

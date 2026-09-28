# Finanzas: costos frente a ganancias — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Registrar desde el Command Center clientes, ventas, gastos y correcciones reales (🔴, solo el fundador) y ver costos, ganancias y balance en una pantalla "Finanzas" y en el chat.

**Architecture:** Un cálculo puro (`FinanceCalculator`) arma libro y totales desde filas crudas (`FinanceMovement`) que lee `FinanceMemoryService` (Cypher a mano: `Transaction` existente + `Expense` y `Correction` nuevos). `FinanceService` valida, genera ids, persiste y publica eventos; lo usan `FinanceController`, `CustomerService.netProfit` y `ChatIntentRouter`. La pantalla React consume `/api/company/finance/**`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Neo4j driver plano, Kafka, JUnit 5 + Mockito; React + Vite + TS + react-query.

**Spec:** `docs/superpowers/specs/2026-09-27-finanzas-costos-ganancias-design.md`

## Global Constraints

- Ningún registro financiero se edita ni se borra; las correcciones son asientos (`Correction`) que apuntan a una venta o gasto.
- Misión opcional en cliente, venta y gasto; entorno `PRODUCTION` por defecto, el de la misión si hay; `TEST` se lista pero no suma.
- Evidencia: `evidenceDescription` obligatoria; `evidenceLink` opcional y, si viene, `http(s)`; se guarda `sourceType:'FOUNDER_DECLARED'`, `verified:false`, `agentId:'human'`.
- Solo USD. Montos: `revenueUsd ≥ 0`, `costUsd ≥ 0`, `amountUsd > 0`; ajustes con signo, no ambos cero.
- Ingresos = Σ revenue + Σ revenueAdjustment; Costos = Σ costUsd + Σ gastos + Σ costAdjustment; Ganancias = Ingresos − Costos; Balance = `SEED_CAPITAL_USD` + Ganancias.
- Jackson 3 (`tools.jackson.*`); errores de validación = `IllegalArgumentException` (convención: sin manejo nuevo en controllers).
- Eventos `EMPRESA_CUSTOMER_REGISTERED`, `EMPRESA_SALE_RECORDED`, `EMPRESA_EXPENSE_RECORDED`, `EMPRESA_CORRECTION_RECORDED`, `agentId="human"`.
- Chat: todo formateado en Java; `api/types.ts` sincronizado a mano; nueva ruta también en `SpaController`.

## Review Focus

- Una corrección cuyo `targetId` es otra corrección → rechazada con mensaje claro (test en Task 2).
- Venta con cliente que es un LEAD de los agentes → rechazada: un prospecto no es cliente (test en Task 2).
- Transacciones viejas registradas por `/missions/{id}/transactions` (sin `environment` propio) → toman el entorno de su misión, con la regla de misiones viejas (sin campo = `TEST`, salvo `MISSION-001`) (Cypher en Task 2, cálculo cubierto en Task 1 por el entorno de la fila).
- Montos con decimales (US$19.99) → totales exactos a 2 decimales en pantalla y chat (test en Task 1).
- "¿Cuánto hemos gastado?" sin ningún movimiento → responde ceros y el capital semilla, sin inventar (test en Task 4).

---

### Task 1: Cálculo puro de libro y totales

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/FinanceMovement.java`, `FinanceEntry.java`, `FinanceSummary.java`
- Create: `app/src/main/java/com/aicompany/core/service/FinanceCalculator.java`
- Test: `app/src/test/java/com/aicompany/core/service/FinanceCalculatorTest.java`

**Interfaces:**
- Produces:
  - `record FinanceMovement(String id, String kind /* SALE|EXPENSE|CORRECTION */, String description, double revenueUsd, double costUsd, String missionId, String environment, Instant recordedAt, String targetId, String counterparty)`. Para `EXPENSE`, `costUsd` = monto del gasto; para `CORRECTION`, `revenueUsd`/`costUsd` = ajustes con signo y `missionId`/`environment` ya heredados del movimiento corregido.
  - `record FinanceEntry(String movementId, String type /* SALE_REVENUE|SALE_COST|EXPENSE|CORRECTION */, String description, double amountUsd, String missionId, String environment, Instant occurredAt, Double runningBalanceUsd, String targetId)`.
  - `record FinanceSummary(double seedCapitalUsd, double revenueUsd, double costsUsd, double profitUsd, double balanceUsd, List<FinanceEntry> entries)`.
  - `static FinanceSummary FinanceCalculator.summarize(List<FinanceMovement> movements, double seedCapitalUsd, String missionIdOrNull)`.

- [ ] **Step 1: Test que falla**

```java
package com.aicompany.core.service;

import com.aicompany.core.model.FinanceMovement;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FinanceCalculatorTest {

    private static FinanceMovement sale(String id, double revenue, double cost, String mission, String env, int minute) {
        return new FinanceMovement(id, "SALE", "venta " + id, revenue, cost, mission, env,
                Instant.parse("2026-09-27T10:00:00Z").plusSeconds(minute * 60L), null, "Cliente A");
    }

    private static FinanceMovement expense(String id, double amount, String mission, String env, int minute) {
        return new FinanceMovement(id, "EXPENSE", "gasto " + id, 0, amount, mission, env,
                Instant.parse("2026-09-27T10:00:00Z").plusSeconds(minute * 60L), null, null);
    }

    private static FinanceMovement correction(String id, String target, double revenueAdj, double costAdj, String mission,
                                              String env, int minute) {
        return new FinanceMovement(id, "CORRECTION", "Duplicada", revenueAdj, costAdj, mission, env,
                Instant.parse("2026-09-27T10:00:00Z").plusSeconds(minute * 60L), target, null);
    }

    @Test
    void costsAndProfitIncludeSalesExpensesAndCorrections() {
        var summary = FinanceCalculator.summarize(List.of(
                sale("S1", 100, 20, "MISSION-1", "PRODUCTION", 1),
                expense("E1", 12, null, "PRODUCTION", 2),
                sale("S2", 100, 20, "MISSION-1", "PRODUCTION", 3),
                correction("C1", "S2", -100, -20, "MISSION-1", "PRODUCTION", 4)), 50, null);

        assertEquals(100.0, summary.revenueUsd(), 1e-9);
        assertEquals(32.0, summary.costsUsd(), 1e-9);
        assertEquals(68.0, summary.profitUsd(), 1e-9);
        assertEquals(118.0, summary.balanceUsd(), 1e-9);
    }

    @Test
    void theLedgerIsChronologicalWithSignedAmountsAndARunningBalance() {
        var summary = FinanceCalculator.summarize(List.of(
                expense("E1", 12, null, "PRODUCTION", 2),
                sale("S1", 100, 20, null, "PRODUCTION", 1)), 50, null);

        var types = summary.entries().stream().map(e -> e.type()).toList();
        assertEquals(List.of("SALE_REVENUE", "SALE_COST", "EXPENSE"), types);
        assertEquals(List.of(100.0, -20.0, -12.0), summary.entries().stream().map(e -> e.amountUsd()).toList());
        assertEquals(List.of(150.0, 130.0, 118.0), summary.entries().stream().map(e -> e.runningBalanceUsd()).toList());
    }

    @Test
    void testMovementsAreListedButNeverCounted() {
        var summary = FinanceCalculator.summarize(List.of(
                sale("S1", 100, 0, null, "TEST", 1),
                expense("E1", 12, null, "PRODUCTION", 2)), 50, null);

        assertEquals(0.0, summary.revenueUsd(), 1e-9);
        assertEquals(12.0, summary.costsUsd(), 1e-9);
        assertEquals(2, summary.entries().size());
        assertNull(summary.entries().get(0).runningBalanceUsd());
    }

    @Test
    void aSaleWithoutCostHasOnlyARevenueLine() {
        var summary = FinanceCalculator.summarize(List.of(sale("S1", 30, 0, null, "PRODUCTION", 1)), 50, null);

        assertEquals(1, summary.entries().size());
    }

    @Test
    void perMissionOnlyCountsThatMissionsMovementsAndCorrections() {
        var summary = FinanceCalculator.summarize(List.of(
                sale("S1", 100, 20, "MISSION-1", "PRODUCTION", 1),
                expense("E1", 12, null, "PRODUCTION", 2),
                expense("E2", 5, "MISSION-1", "PRODUCTION", 3),
                correction("C1", "E2", 0, -5, "MISSION-1", "PRODUCTION", 4),
                sale("S9", 999, 0, "MISSION-9", "PRODUCTION", 5)), 50, "MISSION-1");

        assertEquals(100.0, summary.revenueUsd(), 1e-9);
        assertEquals(20.0, summary.costsUsd(), 1e-9);
        assertEquals(4, summary.entries().size());
    }

    @Test
    void centsAddUpExactly() {
        var summary = FinanceCalculator.summarize(List.of(
                sale("S1", 19.99, 0, null, "PRODUCTION", 1),
                sale("S2", 0.01, 0, null, "PRODUCTION", 2),
                expense("E1", 0.1, null, "PRODUCTION", 3),
                expense("E2", 0.2, null, "PRODUCTION", 4)), 50, null);

        assertEquals(20.00, summary.revenueUsd());
        assertEquals(0.30, summary.costsUsd());
        assertEquals(19.70, summary.profitUsd());
    }
}
```

- [ ] **Step 2:** `cd app && mvn -q test -Dtest=FinanceCalculatorTest` → FAIL (no compila).
- [ ] **Step 3: Implementación**

```java
// FinanceMovement.java / FinanceEntry.java / FinanceSummary.java en com.aicompany.core.model con las firmas de arriba.

package com.aicompany.core.service;

import com.aicompany.core.model.FinanceEntry;
import com.aicompany.core.model.FinanceMovement;
import com.aicompany.core.model.FinanceSummary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Spec finanzas §2: libro y totales 100% en Java; solo PRODUCTION suma; montos redondeados a centavos. */
public final class FinanceCalculator {

    private FinanceCalculator() {
    }

    public static FinanceSummary summarize(List<FinanceMovement> movements, double seedCapitalUsd, String missionIdOrNull) {
        var selected = movements.stream()
                .filter(m -> missionIdOrNull == null || missionIdOrNull.equals(m.missionId()))
                .sorted(Comparator.comparing(FinanceMovement::recordedAt))
                .toList();

        var entries = new ArrayList<FinanceEntry>();
        var revenue = BigDecimal.ZERO;
        var costs = BigDecimal.ZERO;
        var running = cents(seedCapitalUsd);

        for (var m : selected) {
            var counts = "PRODUCTION".equals(m.environment());
            var lines = switch (m.kind()) {
                case "SALE" -> m.costUsd() > 0
                        ? List.of(line(m, "SALE_REVENUE", m.revenueUsd()), line(m, "SALE_COST", -m.costUsd()))
                        : List.of(line(m, "SALE_REVENUE", m.revenueUsd()));
                case "EXPENSE" -> List.of(line(m, "EXPENSE", -m.costUsd()));
                case "CORRECTION" -> List.of(line(m, "CORRECTION", m.revenueUsd() - m.costUsd()));
                default -> List.<FinanceEntry>of();
            };
            if (counts) {
                revenue = revenue.add(cents(m.revenueUsd()));
                costs = costs.add(cents(m.costUsd()));
            }
            for (var entry : lines) {
                if (counts) {
                    running = running.add(cents(entry.amountUsd()));
                }
                entries.add(new FinanceEntry(entry.movementId(), entry.type(), entry.description(), entry.amountUsd(),
                        entry.missionId(), entry.environment(), entry.occurredAt(),
                        counts ? running.doubleValue() : null, entry.targetId()));
            }
        }

        var profit = revenue.subtract(costs);
        return new FinanceSummary(cents(seedCapitalUsd).doubleValue(), revenue.doubleValue(), costs.doubleValue(),
                profit.doubleValue(), cents(seedCapitalUsd).add(profit).doubleValue(), entries);
    }

    private static FinanceEntry line(FinanceMovement m, String type, double amount) {
        return new FinanceEntry(m.id(), type, m.description(), cents(amount).doubleValue(), m.missionId(),
                m.environment(), m.recordedAt(), null, m.targetId());
    }

    private static BigDecimal cents(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4:** `mvn -q test -Dtest=FinanceCalculatorTest` → PASS; `bash /tmp/suite.sh` → verde.
- [ ] **Step 5: Commit** `git commit -m "Finanzas: cálculo puro de libro, costos, ganancias y balance"`

---

### Task 2: Persistencia y servicio (clientes, ventas, gastos, correcciones)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/FinanceMemoryService.java` (Neo4j, sin test directo)
- Create: `app/src/main/java/com/aicompany/core/service/FinanceService.java`
- Create: `app/src/main/java/com/aicompany/core/model/FinanceCustomerCommand.java`, `FinanceSaleCommand.java`, `FinanceExpenseCommand.java`, `FinanceCorrectionCommand.java`, `FinanceCustomer.java`
- Modify: `app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java` (o donde se crean constraints: `grep -rn "CREATE CONSTRAINT" app/src/main`) — constraints `expense_id`, `correction_id`
- Test: `app/src/test/java/com/aicompany/core/service/FinanceServiceTest.java`

**Interfaces:**
- Consumes: `FinanceCalculator.summarize` (Task 1), `CompanyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD)`, `CompanyEventPublisher.publish(type, missionId, taskId, agentId, Map)`.
- Produces:
  - Commands: `FinanceCustomerCommand(String name, String contact, String missionId, String evidenceDescription, String evidenceLink)`; `FinanceSaleCommand(String customerId, String description, double revenueUsd, double costUsd, String missionId, String environment, String evidenceDescription, String evidenceLink)`; `FinanceExpenseCommand(String description, double amountUsd, String missionId, String environment, String evidenceDescription, String evidenceLink)`; `FinanceCorrectionCommand(String targetId, double revenueAdjustmentUsd, double costAdjustmentUsd, String reason, String evidenceDescription, String evidenceLink)`.
  - `record FinanceCustomer(String id, String name, String contact, String missionId, Instant recordedAt)`.
  - `FinanceMemoryService`: `Optional<String> missionEnvironment(String missionId)`; `Optional<String> customerKind(String customerId)` (`"CUSTOMER"` o `"LEAD"`); `Optional<String> movementKind(String id)` (`SALE|EXPENSE|CORRECTION`); `void createCustomer(String id, String name, String contact, String missionId, String evidenceDescription, String evidenceLink)`; `void createSale(String id, FinanceSaleCommand c, String environment)`; `void createExpense(String id, FinanceExpenseCommand c, String environment)`; `void createCorrection(String id, FinanceCorrectionCommand c)`; `List<FinanceMovement> movements()`; `List<FinanceCustomer> customers()`.
  - `FinanceService`: `FinanceSummary summary(String missionIdOrNull)`; `List<FinanceCustomer> customers()`; `FinanceCustomer registerCustomer(FinanceCustomerCommand)`; `String registerSale(FinanceSaleCommand)`; `String registerExpense(FinanceExpenseCommand)`; `String registerCorrection(FinanceCorrectionCommand)` (devuelven el id).

- [ ] **Step 1: Tests que fallan** (`FinanceServiceTest`, con `FinanceMemoryService memory`, `CompanyPolicyService policies`, `CompanyEventPublisher events` mockeados y `new FinanceService(memory, policies, events)`):

```java
@Test
void aSaleWithoutMissionIsProductionAndPublishesAnEvent() {
    when(memory.customerKind("C1")).thenReturn(Optional.of("CUSTOMER"));

    var id = service.registerSale(new FinanceSaleCommand("C1", "Landing", 120, 10, null, null, "Pago por Nequi", null));

    verify(memory).createSale(eq(id), any(), eq("PRODUCTION"));
    verify(events).publish(eq("EMPRESA_SALE_RECORDED"), isNull(), isNull(), eq("human"), anyMap());
}

@Test
void aMovementOfAMissionTakesTheMissionsEnvironment() {
    when(memory.missionEnvironment("MISSION-1")).thenReturn(Optional.of("TEST"));

    var id = service.registerExpense(new FinanceExpenseCommand("Dominio", 12, "MISSION-1", null, "Recibo", null));

    verify(memory).createExpense(eq(id), any(), eq("TEST"));
}

@Test
void aProspectIsNotACustomer() {
    when(memory.customerKind("LEAD-1")).thenReturn(Optional.of("LEAD"));

    var ex = assertThrows(IllegalArgumentException.class, () -> service.registerSale(
            new FinanceSaleCommand("LEAD-1", "x", 10, 0, null, null, "recibo", null)));
    assertTrue(ex.getMessage().contains("prospecto"), ex.getMessage());
}

@Test
void invalidInputIsRejectedWithAClearMessage() {
    assertThrows(IllegalArgumentException.class, () -> service.registerExpense(
            new FinanceExpenseCommand("Dominio", 12, null, null, " ", null)));
    assertThrows(IllegalArgumentException.class, () -> service.registerExpense(
            new FinanceExpenseCommand("Dominio", 12, null, null, "Recibo", "ftp://x")));
    assertThrows(IllegalArgumentException.class, () -> service.registerExpense(
            new FinanceExpenseCommand("Dominio", 0, null, null, "Recibo", null)));
    assertThrows(IllegalArgumentException.class, () -> service.registerExpense(
            new FinanceExpenseCommand("Dominio", 12, "MISSION-404", null, "Recibo", null)));
    verify(memory, never()).createExpense(any(), any(), any());
}

@Test
void aCorrectionMustPointToASaleOrAnExpense() {
    when(memory.movementKind("C1")).thenReturn(Optional.of("CORRECTION"));
    when(memory.movementKind("X")).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service.registerCorrection(
            new FinanceCorrectionCommand("C1", -10, 0, "error", "nota", null)));
    assertThrows(IllegalArgumentException.class, () -> service.registerCorrection(
            new FinanceCorrectionCommand("X", -10, 0, "error", "nota", null)));
    verify(memory, never()).createCorrection(any(), any());
}

@Test
void anExpenseCorrectionCanOnlyAdjustCostsAndNeedsAReason() {
    when(memory.movementKind("E1")).thenReturn(Optional.of("EXPENSE"));

    assertThrows(IllegalArgumentException.class, () -> service.registerCorrection(
            new FinanceCorrectionCommand("E1", -5, 0, "error", "nota", null)));
    assertThrows(IllegalArgumentException.class, () -> service.registerCorrection(
            new FinanceCorrectionCommand("E1", 0, -5, " ", "nota", null)));

    var id = service.registerCorrection(new FinanceCorrectionCommand("E1", 0, -5, "Cobro duplicado", "Extracto", null));

    verify(memory).createCorrection(eq(id), any());
    verify(events).publish(eq("EMPRESA_CORRECTION_RECORDED"), isNull(), isNull(), eq("human"), anyMap());
}

@Test
void theSummaryUsesTheSeedCapitalPolicy() {
    when(policies.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(50.0);
    when(memory.movements()).thenReturn(List.of());

    var summary = service.summary(null);

    assertEquals(50.0, summary.balanceUsd());
    assertEquals(0.0, summary.costsUsd());
}
```
- [ ] **Step 2:** `mvn -q test -Dtest=FinanceServiceTest` → FAIL (no compila).
- [ ] **Step 3: Implementación.**

`FinanceService`:

```java
@Service
public class FinanceService {

    private final FinanceMemoryService memory;
    private final CompanyPolicyService policies;
    private final CompanyEventPublisher events;

    public FinanceService(FinanceMemoryService memory, CompanyPolicyService policies, CompanyEventPublisher events) {
        this.memory = memory;
        this.policies = policies;
        this.events = events;
    }

    public FinanceSummary summary(String missionIdOrNull) {
        return FinanceCalculator.summarize(memory.movements(), policies.activeValue(PolicyKey.SEED_CAPITAL_USD),
                missionIdOrNull == null || missionIdOrNull.isBlank() ? null : missionIdOrNull);
    }

    public List<FinanceCustomer> customers() {
        return memory.customers();
    }

    public FinanceCustomer registerCustomer(FinanceCustomerCommand c) {
        require(c.name(), "El nombre del cliente es obligatorio.");
        validateEvidence(c.evidenceDescription(), c.evidenceLink());
        environmentFor(c.missionId(), null);
        var id = "CUSTOMER-" + UUID.randomUUID();
        memory.createCustomer(id, c.name().strip(), c.contact(), blankToNull(c.missionId()), c.evidenceDescription().strip(),
                blankToNull(c.evidenceLink()));
        events.publish("EMPRESA_CUSTOMER_REGISTERED", blankToNull(c.missionId()), null, "human",
                Map.of("customerId", id, "name", c.name().strip()));
        return new FinanceCustomer(id, c.name().strip(), c.contact(), blankToNull(c.missionId()), Instant.now());
    }

    public String registerSale(FinanceSaleCommand c) {
        require(c.description(), "La descripción de la venta es obligatoria.");
        if (c.revenueUsd() < 0 || c.costUsd() < 0) {
            throw new IllegalArgumentException("Ingreso y costo no pueden ser negativos.");
        }
        validateEvidence(c.evidenceDescription(), c.evidenceLink());
        var kind = memory.customerKind(c.customerId())
                .orElseThrow(() -> new IllegalArgumentException("No existe el cliente " + c.customerId() + "."));
        if ("LEAD".equals(kind)) {
            throw new IllegalArgumentException("El cliente " + c.customerId() + " es un prospecto de los agentes, no un "
                    + "cliente: regístralo como cliente cuando compre.");
        }
        var environment = environmentFor(c.missionId(), c.environment());
        var id = "SALE-" + UUID.randomUUID();
        memory.createSale(id, c, environment);
        events.publish("EMPRESA_SALE_RECORDED", blankToNull(c.missionId()), null, "human",
                Map.of("saleId", id, "revenueUsd", c.revenueUsd(), "costUsd", c.costUsd(), "environment", environment));
        return id;
    }

    public String registerExpense(FinanceExpenseCommand c) {
        require(c.description(), "La descripción del gasto es obligatoria.");
        if (c.amountUsd() <= 0) {
            throw new IllegalArgumentException("El monto del gasto debe ser mayor a 0.");
        }
        validateEvidence(c.evidenceDescription(), c.evidenceLink());
        var environment = environmentFor(c.missionId(), c.environment());
        var id = "EXPENSE-" + UUID.randomUUID();
        memory.createExpense(id, c, environment);
        events.publish("EMPRESA_EXPENSE_RECORDED", blankToNull(c.missionId()), null, "human",
                Map.of("expenseId", id, "amountUsd", c.amountUsd(), "environment", environment));
        return id;
    }

    public String registerCorrection(FinanceCorrectionCommand c) {
        require(c.reason(), "El motivo de la corrección es obligatorio.");
        validateEvidence(c.evidenceDescription(), c.evidenceLink());
        var kind = memory.movementKind(c.targetId())
                .orElseThrow(() -> new IllegalArgumentException("No existe el movimiento " + c.targetId() + "."));
        if ("CORRECTION".equals(kind)) {
            throw new IllegalArgumentException("Una corrección no se corrige: corrige otra vez el movimiento original.");
        }
        if ("EXPENSE".equals(kind) && c.revenueAdjustmentUsd() != 0) {
            throw new IllegalArgumentException("Un gasto solo admite ajuste de costo.");
        }
        if (c.revenueAdjustmentUsd() == 0 && c.costAdjustmentUsd() == 0) {
            throw new IllegalArgumentException("La corrección no cambia nada: indica un ajuste de ingreso o de costo.");
        }
        var id = "CORRECTION-" + UUID.randomUUID();
        memory.createCorrection(id, c);
        events.publish("EMPRESA_CORRECTION_RECORDED", null, null, "human", Map.of("correctionId", id,
                "targetId", c.targetId(), "revenueAdjustmentUsd", c.revenueAdjustmentUsd(),
                "costAdjustmentUsd", c.costAdjustmentUsd(), "reason", c.reason().strip()));
        return id;
    }

    private String environmentFor(String missionId, String requested) {
        if (missionId == null || missionId.isBlank()) {
            return requested == null || requested.isBlank() ? "PRODUCTION" : requested.strip().toUpperCase(Locale.ROOT);
        }
        return memory.missionEnvironment(missionId)
                .orElseThrow(() -> new IllegalArgumentException("No existe la misión " + missionId + "."));
    }

    private static void validateEvidence(String description, String link) {
        require(description, "La evidencia necesita una descripción (p. ej. \"factura #123\").");
        if (link != null && !link.isBlank() && !link.strip().matches("(?i)^https?://\\S+$")) {
            throw new IllegalArgumentException("El comprobante debe ser un link http(s).");
        }
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
```

`FinanceMemoryService` (Cypher; revisar antes con `grep -n "coalesce(m.environment" -r app/src/main` cómo se lee hoy el entorno de misiones viejas y copiar esa regla):

- `missionEnvironment`: `MATCH (m:Mission {id:$id}) RETURN CASE WHEN m.environment IS NOT NULL THEN m.environment WHEN m.id = 'MISSION-001' THEN 'PRODUCTION' ELSE 'TEST' END AS e`.
- `customerKind`: `MATCH (c:Customer {id:$id}) RETURN CASE WHEN c.status = 'LEAD' THEN 'LEAD' ELSE 'CUSTOMER' END AS k`.
- `movementKind`: `OPTIONAL MATCH (t:Transaction {id:$id}) OPTIONAL MATCH (e:Expense {id:$id}) OPTIONAL MATCH (c:Correction {id:$id}) RETURN CASE WHEN t IS NOT NULL THEN 'SALE' WHEN e IS NOT NULL THEN 'EXPENSE' WHEN c IS NOT NULL THEN 'CORRECTION' END AS k` (vacío si es null).
- `createCustomer`: `CREATE (c:Customer {id, name, contact, recordedAt})` + `CREATE (ev:Evidence {id: randomUUID(), description, source: $link, sourceType:'FOUNDER_DECLARED', verified:false, agentId:'human', createdAt})` + `CREATE (c)-[:HAS_EVIDENCE]->(ev)` + si `missionId`: `MATCH (m:Mission {id:$missionId}) MERGE (m)-[:HAS_CUSTOMER]->(c)`.
- `createSale`: `MATCH (cu:Customer {id:$customerId}) CREATE (t:Transaction {id, customerId, description, revenueUsd, costUsd, netProfitUsd, environment, missionId, recordedAt})-[:FOR_CUSTOMER]->(cu)` + evidencia igual + misión opcional `MERGE (m)-[:HAS_TRANSACTION]->(t)`.
- `createExpense`: `CREATE (e:Expense {id, description, amountUsd, environment, missionId, recordedAt})` + evidencia + misión opcional `MERGE (m)-[:HAS_EXPENSE]->(e)`.
- `createCorrection`: `MATCH (target {id:$targetId}) WHERE target:Transaction OR target:Expense CREATE (c:Correction {id, targetId, revenueAdjustmentUsd, costAdjustmentUsd, reason, recordedAt})-[:CORRECTS]->(target)` + evidencia.
- `movements()` (una query por tipo, unidas en Java):
  - ventas: `MATCH (t:Transaction) OPTIONAL MATCH (m:Mission)-[:HAS_TRANSACTION]->(t) OPTIONAL MATCH (t)-[:FOR_CUSTOMER]->(cu:Customer) RETURN t.id, t.description, t.revenueUsd, t.costUsd, coalesce(t.missionId, m.id) AS missionId, coalesce(t.environment, CASE WHEN m IS NULL THEN 'PRODUCTION' WHEN m.environment IS NOT NULL THEN m.environment WHEN m.id='MISSION-001' THEN 'PRODUCTION' ELSE 'TEST' END) AS environment, t.recordedAt, cu.name`.
  - gastos: `MATCH (e:Expense) RETURN e.id, e.description, e.amountUsd, e.missionId, e.environment, e.recordedAt`.
  - correcciones: `MATCH (c:Correction)-[:CORRECTS]->(target)` + mismo cálculo de `missionId`/`environment` del target (Transaction con su misión o Expense) → `FinanceMovement("CORRECTION", reason como description, revenueAdjustmentUsd, costAdjustmentUsd, …, targetId)`.
  - `recordedAt` se guarda como texto ISO (como `Transaction` hoy) y se parsea con `Instant.parse`.
- `customers()`: `MATCH (c:Customer) WHERE c.status IS NULL OPTIONAL MATCH (m:Mission)-[:HAS_CUSTOMER]->(c) RETURN c.id, c.name, c.contact, m.id, c.recordedAt ORDER BY c.name`.

Constraints: agregar `CREATE CONSTRAINT expense_id IF NOT EXISTS FOR (e:Expense) REQUIRE e.id IS UNIQUE` y `correction_id` para `Correction`, junto a las existentes.
- [ ] **Step 4:** `mvn -q test -Dtest=FinanceServiceTest` → PASS; suite verde.
- [ ] **Step 5: Commit** `git commit -m "Finanzas: persistencia y servicio de clientes, ventas, gastos y correcciones"`

---

### Task 3: API y `net-profit` por misión con el mismo cálculo

**Files:**
- Create: `app/src/main/java/com/aicompany/core/controller/FinanceController.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CustomerService.java` (`netProfit`)
- Test: `app/src/test/java/com/aicompany/core/controller/FinanceControllerTest.java`, `app/src/test/java/com/aicompany/core/service/CustomerServiceTest.java`

**Interfaces:**
- Consumes: `FinanceService` (Task 2).
- Produces: `GET /api/company/finance?missionId=`, `GET|POST /api/company/finance/customers`, `POST /api/company/finance/sales|expenses|corrections` (responden `Map.of("id", id)`).

- [ ] **Step 1: Tests que fallan.**

```java
class FinanceControllerTest {
    private final FinanceService finance = mock(FinanceService.class);
    private final FinanceController controller = new FinanceController(finance);

    @Test
    void everyEndpointDelegatesToTheFinanceService() {
        var summary = new FinanceSummary(50, 0, 0, 0, 50, List.of());
        when(finance.summary("MISSION-1")).thenReturn(summary);
        when(finance.registerExpense(any())).thenReturn("EXPENSE-1");

        assertEquals(summary, controller.summary("MISSION-1"));
        assertEquals(Map.of("id", "EXPENSE-1"),
                controller.expense(new FinanceExpenseCommand("Dominio", 12, null, null, "Recibo", null)));
    }
}
```

En `CustomerServiceTest`: `netProfit` usa `FinanceService.summary(missionId)`:

```java
@Test
void netProfitOfAMissionIncludesItsExpensesAndCorrections() {
    when(finance.summary("MISSION-1")).thenReturn(new FinanceSummary(50, 100, 32, 68, 118, List.of()));
    when(companyPolicyService.activeValue(any())).thenReturn(50.0);

    var response = service.netProfit("MISSION-1");

    assertEquals(100.0, response.totalRevenue());
    assertEquals(32.0, response.totalCost());
    assertEquals(68.0, response.netProfit());
}
```

(`CustomerService` gana `FinanceService` como 5º argumento del constructor; actualizar el armado del test. Revisar los nombres reales de los campos de `MissionProfitResponse` y ajustar.)
- [ ] **Step 2:** `mvn -q test -Dtest='FinanceControllerTest,CustomerServiceTest'` → FAIL.
- [ ] **Step 3: Implementación.**

```java
@RestController
@RequestMapping("/api/company/finance")
public class FinanceController {

    private final FinanceService finance;

    public FinanceController(FinanceService finance) {
        this.finance = finance;
    }

    @GetMapping
    public FinanceSummary summary(@RequestParam(required = false) String missionId) {
        return finance.summary(missionId);
    }

    @GetMapping("/customers")
    public List<FinanceCustomer> customers() {
        return finance.customers();
    }

    @PostMapping("/customers")
    public FinanceCustomer customer(@RequestBody FinanceCustomerCommand command) {
        return finance.registerCustomer(command);
    }

    @PostMapping("/sales")
    public Map<String, String> sale(@RequestBody FinanceSaleCommand command) {
        return Map.of("id", finance.registerSale(command));
    }

    @PostMapping("/expenses")
    public Map<String, String> expense(@RequestBody FinanceExpenseCommand command) {
        return Map.of("id", finance.registerExpense(command));
    }

    @PostMapping("/corrections")
    public Map<String, String> correction(@RequestBody FinanceCorrectionCommand command) {
        return Map.of("id", finance.registerCorrection(command));
    }
}
```

`CustomerService.netProfit`: reemplazar `memory.totalRevenueAndCost(missionId)` por `finance.summary(missionId)` (`revenueUsd`, `costsUsd`, `profitUsd`), resto igual.
- [ ] **Step 4:** tests → PASS; suite verde.
- [ ] **Step 5: Commit** `git commit -m "Finanzas: API /api/company/finance y net-profit por misión con gastos y correcciones"`

---

### Task 4: Chat

**Files:** Modify `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`, `CeoService.java` (descripción del topic `COMPANY_PROFIT`). Test: `ChatIntentRouterTest.java`.

**Interfaces:** Consumes `FinanceService.summary(String)` (Task 2); `ChatIntentRouter` gana `FinanceService` en el constructor (actualizar el armado en `ChatIntentRouterTest` y cualquier otro `new ChatIntentRouter(`).

- [ ] **Step 1: Tests que fallan:**

```java
@Test
void costsVersusProfitIsAnsweredFromJavaWithTheLatestMovements() {
    when(finance.summary(null)).thenReturn(new FinanceSummary(50, 100, 32, 68, 118, List.of(
            new FinanceEntry("E1", "EXPENSE", "Dominio forjai.com", -12, null, "PRODUCTION",
                    Instant.parse("2026-09-27T10:00:00Z"), 118.0, null),
            new FinanceEntry("C1", "CORRECTION", "Duplicada", -80, "MISSION-1", "PRODUCTION",
                    Instant.parse("2026-09-27T11:00:00Z"), 38.0, "S2"))));

    var response = router.route("¿cómo vamos en costos vs ganancias?");

    assertTrue(response.contains("Costos: US$32.00"), response);
    assertTrue(response.contains("Ganancias: US$68.00"), response);
    assertTrue(response.contains("Balance: US$118.00"), response);
    assertTrue(response.contains("Dominio forjai.com"), response);
    assertTrue(response.contains("corrige S2"), response);
    verifyNoInteractions(ceoService);
}

@Test
void withNoMovementsTheChatSaysZerosAndTheSeedCapital() {
    when(finance.summary(null)).thenReturn(new FinanceSummary(50, 0, 0, 0, 50, List.of()));

    var response = router.route("¿cuánto hemos gastado?");

    assertTrue(response.contains("Costos: US$0.00") && response.contains("Balance: US$50.00"), response);
    assertTrue(response.contains("Todavía no hay movimientos"), response);
}

@Test
void theCompanyStatusAddsCostsAndBalance() {
    // mismo armado que routesCompanyStatusQueryToADeterministicAggregateSnapshot
    when(finance.summary(null)).thenReturn(new FinanceSummary(50, 150, 62, 88, 138, List.of()));
    var response = router.route("dame un status");
    assertTrue(response.contains("Costos: US$62.00") && response.contains("Balance: US$138.00"), response);
}

@Test
void aMissionWithMovementsShowsItsFinances() {
    // misión MISSION-5 como en missionStatusGroupsTasksByRoundAndShowsEachRequest (sin rondas)
    when(finance.summary("MISSION-5")).thenReturn(new FinanceSummary(50, 100, 20, 80, 130, List.of(
            new FinanceEntry("S1", "SALE_REVENUE", "Landing", 100, "MISSION-5", "PRODUCTION", Instant.now(), 150.0, null))));
    var response = router.route("¿Cómo va MISSION-5?");
    assertTrue(response.contains("Finanzas de la misión: ingresos US$100.00, costos US$20.00, ganancias US$80.00"), response);
}
```

Actualizar `routesCompanyStatusQueryToADeterministicAggregateSnapshot` para stubear `finance.summary(null)` (los números de ingresos y beneficio del status pasan a salir de `FinanceService`).
- [ ] **Step 2:** `mvn -q test -Dtest=ChatIntentRouterTest` → FAIL.
- [ ] **Step 3: Implementación.**
  - Keywords de `COMPANY_PROFIT` en `detectQuery`: sumar `"costo"`, `"movimiento"`, `"balance"`, `"finanza"`, `"ingreso"` a las actuales.
  - `formatCompanyProfit()` (sin argumento) desde `finance.summary(null)`:
    ```java
    private String formatFinance(FinanceSummary s, int lastN) {
        var head = String.format(Locale.ROOT, "Costos: US$%.2f. Ganancias: US$%.2f (ingresos US$%.2f). Balance: US$%.2f "
                + "(capital semilla US$%.2f).", s.costsUsd(), s.profitUsd(), s.revenueUsd(), s.balanceUsd(), s.seedCapitalUsd());
        if (s.entries().isEmpty()) {
            return head + " Todavía no hay movimientos registrados.";
        }
        var from = Math.max(0, s.entries().size() - lastN);
        var lines = s.entries().subList(from, s.entries().size()).stream()
                .map(e -> String.format(Locale.ROOT, "%s %s US$%.2f \"%s\"%s%s", e.occurredAt().toString().substring(0, 10),
                        e.type(), e.amountUsd(), e.description(), e.targetId() == null ? "" : " (corrige " + e.targetId() + ")",
                        "TEST".equals(e.environment()) ? " [prueba]" : ""))
                .collect(Collectors.joining("; "));
        return head + " Últimos movimientos: " + lines + ".";
    }
    ```
    y `case "COMPANY_PROFIT" -> formatFinance(financeService.summary(null), 10)` en `answerMemoryTopic`.
  - `formatCompanyStatus`: reemplazar "Ingresos … Beneficio neto …" por `String.format("Ingresos: US$%.2f. Costos: US$%.2f. Ganancias: US$%.2f. Balance: US$%.2f.", …)` desde `finance.summary(null)`; el "capital disponible" del inicio pasa a ser el balance.
  - `formatMissionStatus`: si `finance.summary(missionId).entries()` no está vacío, agregar `" Finanzas de la misión: ingresos US$%.2f, costos US$%.2f, ganancias US$%.2f."`.
  - En `CeoService`, la descripción de `COMPANY_PROFIT` pasa a "costos, ganancias, ingresos, balance y últimos movimientos reales (incluye gastos y correcciones)".
- [ ] **Step 4:** tests → PASS; suite verde.
- [ ] **Step 5: Commit** `git commit -m "Chat: costos frente a ganancias, balance y movimientos desde Java"`

---

### Task 5: Pantalla "Finanzas" en el Command Center

**Files:** Create `app/frontend/src/pages/FinancePage.tsx`; Modify `app/frontend/src/api/types.ts`, `api/client.ts`, `App.tsx`, `components/Layout.tsx`, `app/src/main/java/com/aicompany/core/controller/SpaController.java` (+ su test si lista rutas), `app/frontend/src/index.css` (clases `.finance-*` mínimas).

**Interfaces:** Consumes la API de Task 3.

- [ ] **Step 1: Test que falla (backend):** si existe `SpaControllerTest`, agregar `"/finanzas"` a las rutas esperadas; si no, crear uno mínimo que verifique que `/finanzas` está en el `@GetMapping`. Correr → FAIL.
- [ ] **Step 2: Implementación.**
  - `types.ts`: `FinanceEntry`, `FinanceSummary`, `FinanceCustomer`, `FinanceCustomerCommand`, `FinanceSaleCommand`, `FinanceExpenseCommand`, `FinanceCorrectionCommand` (mismos campos que los records Java).
  - `client.ts`: `finance(missionId?)`, `financeCustomers()`, `createFinanceCustomer(cmd)`, `createSale(cmd)`, `createExpense(cmd)`, `createCorrection(cmd)` con `request` y `method: 'POST'`.
  - `FinancePage.tsx`:
    - `useQuery(['finance', missionFilter])`, `useQuery(['finance-customers'])`, `useQuery(['missions'])` (para el selector).
    - Tarjetas (`dashboard-cards`/`card`): Costos, Ganancias (verde si ≥ 0, rojo si < 0), Ingresos, Balance (con "capital semilla US$X").
    - Selector de misión (todas / cada misión) que filtra el resumen.
    - Tabla (`data-table`): fecha, tipo (Venta, Costo de venta, Gasto, Corrección), descripción (+ "corrige X"), misión (link a `/missions/:id`), monto con signo, balance acumulado, 🧪 si TEST; en ventas y gastos, botón "Corregir" que abre un formulario inline con "Anular" (precarga el negativo: venta → ingreso −revenue y costo −cost; gasto → costo −monto), campos de ajuste, motivo y evidencia.
    - Tres formularios (`decision-form`): Cliente (nombre, contacto, misión, evidencia), Venta (cliente existente, descripción, ingreso, costo, misión, entorno PRODUCTION/TEST, evidencia), Gasto (descripción, monto, misión, entorno, evidencia). Tras cada `useMutation` exitosa, `invalidateQueries(['finance'])` y `['finance-customers']`; los errores del servidor se muestran en `<p className="error">`.
  - `App.tsx`: `<Route path="finanzas" element={<FinancePage />} />`; `Layout.tsx`: `<NavLink to="/finanzas">Finanzas</NavLink>` después de Missions; `SpaController`: `"/finanzas"`.
- [ ] **Step 3:** `mvn -q test` → verde; `cd app/frontend && npm run lint && npm run build` → sin errores nuevos.
- [ ] **Step 4: Commit** `git commit -m "Command Center: pantalla Finanzas (costos vs ganancias, libro, correcciones y formularios)"`

---

### Task 6: Documentación y verificación en vivo

**Files:** `CLAUDE.md`, `docs/HISTORY.md`, `docs/EVENTS.md`.

- [ ] **Step 1:** `CLAUDE.md`: sección "Datos reales del fundador" (Finanzas: `Expense`, `Correction`, misión opcional, cálculo, `/api/company/finance/**`, pantalla), Chat (`COMPANY_PROFIT` con costos/ganancias/balance), Command Center (pantalla Finanzas). `docs/EVENTS.md`: los 4 eventos.
- [ ] **Step 2 (en vivo, tras redeploy seguro):** desde la pantalla (o la misma API que usa) con registros `TEST`: cliente → venta (US$120 / costo 10) → gasto (US$12) → "Anular" la venta; verificar tarjetas, libro y balance acumulado; en el chat "costos vs ganancias" y "dame un status" (los TEST no suman). Después borrar por Cypher los nodos de prueba (`Customer`/`Transaction`/`Expense`/`Correction` de la verificación y sus `Evidence`), mostrando antes qué se borra.
- [ ] **Step 3:** `docs/HISTORY.md`: decisiones del fundador y verificación.
- [ ] **Step 4: Commit** `git commit -m "Documentar finanzas (costos frente a ganancias) y su verificación en vivo"`

---

## Self-review

- **Cobertura del spec**: datos §1 → T2; cálculo §2 → T1 (+ `net-profit` T3); API §3 → T3 (+ eventos T2); pantalla §4 → T5; chat §5 → T4; testing → T1–T5; en vivo → T6.
- **Desvío deliberado del spec**: en vez de un `QueryIntent.FINANCE` nuevo se amplía el `COMPANY_PROFIT` existente (ya capturaba "gasto"/"ganancia"); el efecto para el fundador es el mismo y evita dos intents que compiten por las mismas palabras.
- **Tipos**: `FinanceMovement`/`FinanceEntry`/`FinanceSummary` (T1) usados en T2–T5; commands (T2) en T3/T5; `FinanceService.summary(String)` en T3/T4.

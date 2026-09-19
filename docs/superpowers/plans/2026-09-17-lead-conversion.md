# Puente LEAD → Cliente Real Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Darle al fundador humano una forma real de ver los `Customer{status:'LEAD'}` que generan los agentes, descartar los que no sirven, y convertir uno en cliente real sin duplicar el conteo de prospectos — sin automatizar el contacto ni el cierre de venta en sí.

**Architecture:** `OpportunityMemoryService` (ya dueño de la creación de LEADs) gana el resto de su ciclo de vida: `listLeads()`, `discardLead(leadId, reason)`, `markConverted(leadId)` — los dos últimos como compare-and-swap atómico en Cypher (mismo patrón que `MissionMemoryService.incrementEvidenceRound`), devolviendo `Optional`/`boolean` en vez de lanzar, para que el llamador decida qué excepción corresponde. `CustomerCommand` gana un `leadId` opcional; cuando viene informado, `CustomerService.registerCustomer` llama a `markConverted` antes de crear el cliente real, y `CustomerMemoryService.registerCustomer` agrega `(:Customer)-[:CONVERTED_FROM]->(:Customer {status:'LEAD'})` en la misma transacción. Nuevo `LeadController` expone `GET /api/company/leads` y `POST /api/company/leads/{leadId}/discard`. `ChatIntentRouter` gana un intent `LEADS` determinista.

**Tech Stack:** Spring Boot 4.1.1 / Java 21, `neo4j-java-driver` (Cypher a mano, sin Spring Data Neo4j), JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-17-lead-conversion-design.md`

## Global Constraints

- Trabajar siempre dentro de `app/` para comandos Maven.
- Java 21, Spring Boot 4.1.1. Jackson es `tools.jackson.*` en el código de la app — nunca `com.fasterxml.jackson.*` (no aplica directamente a este trabajo, pero ningún archivo tocado debe agregarlo).
- `OpportunityMemoryService`/`CustomerMemoryService` (y el resto de `*MemoryService`) no llevan test directo — integración Neo4j, mismo criterio ya establecido en el proyecto. La lógica que decide qué excepción lanzar vive en `CustomerService`/`LeadController`, nunca en el `*MemoryService`, siguiendo el patrón ya usado por `MissionMemoryService.incrementEvidenceRound` (retorna `Optional`/`boolean`, el llamador decide).
- Ningún controller de este proyecto maneja HTTP finamente — una `IllegalStateException`/`IllegalArgumentException` sin capturar cae al handler default de Spring (500). No agregar manejo de errores nuevo.
- Sin eventos Kafka nuevos — el flujo humano de `CustomerController`/`CustomerService`/`CustomerMemoryService` no publica nada a Kafka hoy y esta feature mantiene esa convención.
- Sin frontend nuevo — API REST + chat alcanza para esta ronda.
- Tests: `mvn test -Dtest=Clase#metodo` desde `app/`; `mvn test` para toda la suite.

---

### Task 1: `OpportunityMemoryService` — ciclo de vida completo del LEAD

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/LeadResponse.java`
- Modify: `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java`

**Interfaces:**
- Produces (usados por Task 2, 3 y 4): `OpportunityMemoryService.listLeads() -> List<LeadResponse>`, `OpportunityMemoryService.discardLead(String leadId, String reason) -> Optional<LeadResponse>` (vacío si el lead no existe o ya no está en `LEAD`), `OpportunityMemoryService.markConverted(String leadId) -> boolean` (`false` en el mismo caso).

Sin test directo (Cypher puro, mismo criterio ya establecido para el resto de `*MemoryService`).

- [ ] **Step 1: Crear `LeadResponse`**

```java
package com.aicompany.core.model;

import java.time.Instant;

public record LeadResponse(
        String id,
        String name,
        String description,
        String source,
        String sourceType,
        String missionId,
        String opportunityId,
        Instant createdAt
) {
}
```

- [ ] **Step 2: Agregar `listLeads`, `discardLead` y `markConverted` a `OpportunityMemoryService`**

Agregar los imports al principio del archivo (`Optional` todavía no está
importado en este archivo; `LeadResponse` es nuevo):

```java
import com.aicompany.core.model.LeadResponse;
```

y, junto a los imports de `java.util.*` ya existentes (`List`, `Map`):

```java
import java.util.Optional;
```

Insertar los tres métodos después de `recordCandidates` (antes del bloque de `countOpportunities`/`listRecent`):

```java
    /**
     * LEADs activos (`status='LEAD'`) para que el fundador humano decida
     * a quién contactar — antes de esto no existía ningún camino (API,
     * chat o pantalla) para ver estos candidatos, solo un conteo agregado
     * (`CustomerMemoryService.countCustomersAndProspects`). `description`/
     * `source`/`sourceType` viven en el nodo `Evidence` enlazado, no en el
     * `Customer` mismo (ver {@link #recordCandidate}).
     */
    public List<LeadResponse> listLeads() {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (o:Opportunity)-[:HAS_CANDIDATE]->(c:Customer {status:'LEAD'}) " +
                                    "OPTIONAL MATCH (c)-[:HAS_EVIDENCE]->(e:Evidence) " +
                                    "RETURN c.id AS id, c.name AS name, c.missionId AS missionId, " +
                                    "o.id AS opportunityId, c.createdAt AS createdAt, " +
                                    "e.description AS description, e.source AS source, " +
                                    "e.sourceType AS sourceType " +
                                    "ORDER BY c.createdAt DESC")
                    .list(r -> new LeadResponse(
                            r.get("id").asString(),
                            r.get("name").asString(""),
                            r.get("description").asString(""),
                            r.get("source").asString(""),
                            r.get("sourceType").asString(""),
                            r.get("missionId").asString(),
                            r.get("opportunityId").asString(),
                            Instant.parse(r.get("createdAt").asString())
                    ));
        }
    }

    /**
     * Compara-y-actualiza atómicamente, mismo patrón que
     * {@code MissionMemoryService.incrementEvidenceRound}: el {@code WHERE}
     * implícito del `MATCH` (`status:'LEAD'`) hace de guarda — si el lead
     * no existe o ya no está en `LEAD` (ya `CONVERTIDO` o `DESCARTADO`),
     * la consulta no devuelve filas y este método retorna vacío en vez de
     * lanzar. Es el llamador ({@code LeadController}) quien decide qué
     * excepción corresponde.
     */
    public Optional<LeadResponse> discardLead(String leadId, String reason) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {

                var records = tx.run(
                        "MATCH (o:Opportunity)-[:HAS_CANDIDATE]->(c:Customer {id:$id, status:'LEAD'}) " +
                                "OPTIONAL MATCH (c)-[:HAS_EVIDENCE]->(e:Evidence) " +
                                "SET c.status='DESCARTADO', c.discardReason=$reason, " +
                                "c.discardedAt=$now, c.updatedAt=$now " +
                                "RETURN c.id AS id, c.name AS name, c.missionId AS missionId, " +
                                "o.id AS opportunityId, c.createdAt AS createdAt, " +
                                "e.description AS description, e.source AS source, " +
                                "e.sourceType AS sourceType",
                        Map.of(
                                "id", leadId,
                                "reason", reason == null ? "" : reason,
                                "now", Instant.now().toString()
                        )
                ).list();

                return records.stream().findFirst().map(r -> new LeadResponse(
                        r.get("id").asString(),
                        r.get("name").asString(""),
                        r.get("description").asString(""),
                        r.get("source").asString(""),
                        r.get("sourceType").asString(""),
                        r.get("missionId").asString(),
                        r.get("opportunityId").asString(),
                        Instant.parse(r.get("createdAt").asString())
                ));
            });
        }
    }

    /**
     * Igual patrón que {@link #discardLead} pero para la conversión a
     * cliente real — llamado por {@code CustomerService.registerCustomer}
     * cuando el comando trae un {@code leadId}. Solo cambia el `status`;
     * no toca `CustomerMemoryService.registerCustomer` ni crea el
     * `Customer` real (eso sigue siendo, a propósito, el mismo camino
     * humano de siempre).
     */
    public boolean markConverted(String leadId) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {

                var records = tx.run(
                        "MATCH (c:Customer {id:$id, status:'LEAD'}) " +
                                "SET c.status='CONVERTIDO', c.updatedAt=$now " +
                                "RETURN c.id AS id",
                        Map.of("id", leadId, "now", Instant.now().toString())
                ).list();

                return !records.isEmpty();
            });
        }
    }
```

- [ ] **Step 3: Compilar**

Run: `cd app && mvn -DskipTests compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/LeadResponse.java \
        app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java
git commit -m "Agregar listLeads, discardLead y markConverted a OpportunityMemoryService"
```

---

### Task 2: Conversión LEAD → cliente real (`CustomerCommand`/`CustomerMemoryService`/`CustomerService`)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/CustomerCommand.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CustomerMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CustomerService.java`
- Modify: `app/src/test/java/com/aicompany/core/service/CustomerServiceTest.java`

**Interfaces:**
- Consumes: `OpportunityMemoryService.markConverted(String) -> boolean` (Task 1).
- Produces: `CustomerCommand.leadId() -> String` (nullable). `CustomerMemoryService.registerCustomer` gana un 5º parámetro `leadId` (nullable) antes de `evidence`. Comportamiento de `CustomerService.registerCustomer` sin cambio de firma pública.

- [ ] **Step 1: Escribir los tests que fallan — reemplazar `CustomerServiceTest.java` completo**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.CustomerCommand;
import com.aicompany.core.model.TransactionCommand;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerServiceTest {

    private final EvidenceValidationGate evidenceGate = new EvidenceValidationGate();
    private final AppProperties appProperties = new AppProperties("Forjai", 50.0, 60);
    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);

    @Test
    void registersCustomerWhenMissionExistsAndEvidenceIsValid() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.missionExists("MISSION-001")).thenReturn(true);

        var service = new CustomerService(memory, evidenceGate, appProperties, opportunityMemory);

        var command = new CustomerCommand(
                "CUST-1", "Panadería El Sol", "panaderia@example.com", null,
                "Pedido confirmado por WhatsApp", "chat con el dueño", "CUSTOMER", true
        );

        var response = service.registerCustomer("MISSION-001", command);

        assertEquals("CUST-1", response.customerId());
        assertEquals("MISSION-001", response.missionId());
        verify(memory).registerCustomer(
                eq("MISSION-001"), eq("CUST-1"), eq("Panadería El Sol"),
                eq("panaderia@example.com"), isNull(), any(AgentResult.Evidence.class)
        );
        verifyNoInteractions(opportunityMemory);
    }

    @Test
    void rejectsCustomerWhenMissionDoesNotExist() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.missionExists("MISSION-404")).thenReturn(false);

        var service = new CustomerService(memory, evidenceGate, appProperties, opportunityMemory);

        var command = new CustomerCommand(
                "CUST-1", "Panadería El Sol", null, null,
                "Pedido confirmado", null, "INTERNAL", false
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomer("MISSION-404", command));

        verify(memory, never()).registerCustomer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsCustomerWithSemanticallyInvalidEvidence() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.missionExists("MISSION-001")).thenReturn(true);

        var service = new CustomerService(memory, evidenceGate, appProperties, opportunityMemory);

        // verified=true con sourceType=NONE: contradicción semántica.
        var command = new CustomerCommand(
                "CUST-1", "Panadería El Sol", null, null,
                "El negocio existe", null, "NONE", true
        );

        assertThrows(IllegalStateException.class,
                () -> service.registerCustomer("MISSION-001", command));

        verify(memory, never()).registerCustomer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void registersCustomerFromAValidLeadAndMarksItConverted() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.missionExists("MISSION-001")).thenReturn(true);
        when(opportunityMemory.markConverted("MISSION-001-CANDIDATE-SALES-0")).thenReturn(true);

        var service = new CustomerService(memory, evidenceGate, appProperties, opportunityMemory);

        var command = new CustomerCommand(
                "CUST-1", "Panadería El Sol", "panaderia@example.com",
                "MISSION-001-CANDIDATE-SALES-0",
                "Pedido confirmado por WhatsApp", "chat con el dueño", "CUSTOMER", true
        );

        var response = service.registerCustomer("MISSION-001", command);

        assertEquals("CUST-1", response.customerId());
        verify(opportunityMemory).markConverted("MISSION-001-CANDIDATE-SALES-0");
        verify(memory).registerCustomer(
                eq("MISSION-001"), eq("CUST-1"), eq("Panadería El Sol"),
                eq("panaderia@example.com"), eq("MISSION-001-CANDIDATE-SALES-0"),
                any(AgentResult.Evidence.class)
        );
    }

    @Test
    void rejectsConversionWhenLeadIsNotInLeadStatus() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.missionExists("MISSION-001")).thenReturn(true);
        when(opportunityMemory.markConverted("MISSION-001-CANDIDATE-SALES-0")).thenReturn(false);

        var service = new CustomerService(memory, evidenceGate, appProperties, opportunityMemory);

        var command = new CustomerCommand(
                "CUST-1", "Panadería El Sol", null,
                "MISSION-001-CANDIDATE-SALES-0",
                "Pedido confirmado", null, "INTERNAL", false
        );

        assertThrows(IllegalStateException.class,
                () -> service.registerCustomer("MISSION-001", command));

        verify(memory, never()).registerCustomer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void registersTransactionAndComputesNetProfit() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.registerTransaction(
                eq("MISSION-001"), eq("CUST-1"), eq("TX-1"),
                eq("Venta de 10 camisetas"), eq(100.0), eq(40.0),
                any(AgentResult.Evidence.class)
        )).thenReturn(Optional.of("2026-09-13T00:00:00Z"));

        var service = new CustomerService(memory, evidenceGate, appProperties, opportunityMemory);

        var command = new TransactionCommand(
                "TX-1", "CUST-1", "Venta de 10 camisetas", 100.0, 40.0,
                "Comprobante de pago", "https://pagos.example.com/tx/1", "TRANSACTION", true
        );

        var response = service.registerTransaction("MISSION-001", command);

        assertTrue(response.isPresent());
        assertEquals(60.0, response.get().netProfitUsd(), 0.0001);
    }

    @Test
    void returnsEmptyWhenCustomerForTransactionDoesNotExist() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.registerTransaction(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any()
        )).thenReturn(Optional.empty());

        var service = new CustomerService(memory, evidenceGate, appProperties, opportunityMemory);

        var command = new TransactionCommand(
                "TX-1", "CUST-DESCONOCIDO", "Venta", 100.0, 40.0,
                "Comprobante", null, "INTERNAL", false
        );

        var response = service.registerTransaction("MISSION-001", command);

        assertTrue(response.isEmpty());
    }

    @Test
    void aggregatesNetProfitAcrossTransactions() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.totalRevenueAndCost("MISSION-001"))
                .thenReturn(new double[]{200.0, 50.0});

        var service = new CustomerService(memory, evidenceGate, appProperties, opportunityMemory);

        var profit = service.netProfit("MISSION-001");

        assertEquals(200.0, profit.totalRevenueUsd());
        assertEquals(50.0, profit.totalCostUsd());
        assertEquals(150.0, profit.netProfitUsd());
        assertTrue(profit.successCriterionMet());
        assertEquals("MUY_BUENO", profit.successLevel());
    }

    @Test
    void successCriterionNotMetWhenNetProfitBelowSeedCapital() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.totalRevenueAndCost("MISSION-001"))
                .thenReturn(new double[]{30.0, 10.0});

        var service = new CustomerService(memory, evidenceGate, appProperties, opportunityMemory);

        var profit = service.netProfit("MISSION-001");

        assertEquals(20.0, profit.netProfitUsd());
        assertFalse(profit.successCriterionMet());
        assertEquals("NINGUNO", profit.successLevel());
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=CustomerServiceTest`
Expected: FAIL — no compila todavía (`CustomerCommand` no tiene `leadId`, `CustomerService`/`CustomerMemoryService` no tienen la firma nueva).

- [ ] **Step 3: Agregar `leadId` a `CustomerCommand`**

Reemplazar el archivo completo:

```java
package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record CustomerCommand(
        @NotBlank String customerId,
        @NotBlank String name,
        String contact,
        String leadId,
        @NotBlank String evidenceDescription,
        String evidenceSource,
        @NotBlank String evidenceSourceType,
        boolean evidenceVerified
) {
}
```

- [ ] **Step 4: Agregar `leadId` a `CustomerMemoryService.registerCustomer`**

Reemplazar el método completo:

```java
    public void registerCustomer(
            String missionId,
            String customerId,
            String name,
            String contact,
            String leadId,
            AgentResult.Evidence evidence) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var recordedAt = Instant.now().toString();

                tx.run("MATCH (m:Mission {id:$missionId}) " +
                                "MERGE (c:Customer {id:$customerId}) " +
                                "SET c.missionId=$missionId, c.name=$name, " +
                                "c.contact=$contact, c.recordedAt=$recordedAt " +
                                "MERGE (m)-[:HAS_CUSTOMER]->(c)",
                        Map.of(
                                "missionId", missionId,
                                "customerId", customerId,
                                "name", name,
                                "contact", contact == null ? "" : contact,
                                "recordedAt", recordedAt
                        ));

                // Si la misión ya generó su Opportunity (MissionExecutor la
                // crea al consolidar, ver OpportunityMemoryService), este
                // cliente real también queda colgado de ella -- no solo de
                // Mission directamente -- para que el grafo siga el modelo
                // objetivo Mission->Opportunity->Customer. Si todavía no
                // existe (p. ej. la misión no ha llegado a consolidación),
                // el MATCH simplemente no encuentra nada y no pasa nada: no
                // se crea una Opportunity vacía como efecto secundario de
                // registrar un cliente.
                tx.run("MATCH (o:Opportunity {id:$opportunityId}), (c:Customer {id:$customerId}) " +
                                "MERGE (o)-[:HAS_CUSTOMER]->(c)",
                        Map.of(
                                "opportunityId", missionId + "-OPPORTUNITY",
                                "customerId", customerId
                        ));

                tx.run("MATCH (c:Customer {id:$customerId}) " +
                                "MERGE (e:Evidence {id:$evidenceId}) " +
                                "SET e.missionId=$missionId, e.agentId='human', " +
                                "e.description=$description, e.source=$source, " +
                                "e.sourceType=$sourceType, e.verified=$verified, " +
                                "e.updatedAt=$updatedAt " +
                                "MERGE (c)-[:HAS_EVIDENCE]->(e)",
                        Map.of(
                                "customerId", customerId,
                                "evidenceId", customerId + "-EVIDENCE",
                                "missionId", missionId,
                                "description", evidence.description() == null ? "" : evidence.description(),
                                "source", evidence.source() == null ? "" : evidence.source(),
                                "sourceType", evidence.sourceType() == null ? "" : evidence.sourceType(),
                                "verified", evidence.verified(),
                                "updatedAt", recordedAt
                        ));

                // Solo si el cliente real viene de un LEAD que ya se marcó
                // CONVERTIDO (CustomerService llama a
                // OpportunityMemoryService.markConverted antes que esto) --
                // enlaza el cliente real nuevo al LEAD del que salió, sin
                // fusionar los nodos ni tocar el LEAD más allá de su
                // status (ver spec, decisión 2).
                if (leadId != null && !leadId.isBlank()) {

                    tx.run("MATCH (c:Customer {id:$customerId}), (lead:Customer {id:$leadId}) " +
                                    "MERGE (c)-[:CONVERTED_FROM]->(lead)",
                            Map.of(
                                    "customerId", customerId,
                                    "leadId", leadId
                            ));
                }

                return null;
            });
        }
    }
```

- [ ] **Step 5: Actualizar `CustomerService`**

Reemplazar el archivo completo:

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.CustomerCommand;
import com.aicompany.core.model.CustomerResponse;
import com.aicompany.core.model.MissionProfitResponse;
import com.aicompany.core.model.TransactionCommand;
import com.aicompany.core.model.TransactionResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Customer Validation: registrar clientes y ventas reales, distinto de lo
 * que cualquier agente pueda afirmar en un {@code AgentResult}. Ni un
 * cliente ni una venta se aceptan sin evidencia — misma regla de "el
 * agente afirma X ≠ la empresa puede demostrar X" aplicada aquí a quien
 * sea que registre el dato (hoy: el fundador humano; no hay todavía un
 * flujo que permita a un agente llamar este endpoint autónomamente).
 */
@Service
public class CustomerService {

    private static final double GOOD_THRESHOLD = 50.0;
    private static final double VERY_GOOD_THRESHOLD = 100.0;
    private static final double EXCELLENT_THRESHOLD = 1000.0;
    private static final double EXTRAORDINARY_THRESHOLD = 5000.0;

    private final CustomerMemoryService memory;
    private final EvidenceValidationGate evidenceGate;
    private final AppProperties appProperties;
    private final OpportunityMemoryService opportunityMemory;

    public CustomerService(
            CustomerMemoryService memory,
            EvidenceValidationGate evidenceGate,
            AppProperties appProperties,
            OpportunityMemoryService opportunityMemory) {

        this.memory = memory;
        this.evidenceGate = evidenceGate;
        this.appProperties = appProperties;
        this.opportunityMemory = opportunityMemory;
    }

    public CustomerResponse registerCustomer(
            String missionId,
            CustomerCommand command) {

        if (!memory.missionExists(missionId)) {

            throw new IllegalArgumentException(
                    "No existe la misión " + missionId
            );
        }

        if (command.leadId() != null && !command.leadId().isBlank()) {

            var converted = opportunityMemory.markConverted(command.leadId());

            if (!converted) {

                throw new IllegalStateException(
                        "El lead " + command.leadId()
                                + " no existe o ya no está en estado LEAD"
                );
            }
        }

        var evidence = new AgentResult.Evidence(
                command.evidenceDescription(),
                command.evidenceSource(),
                command.evidenceSourceType(),
                command.evidenceVerified()
        );

        var validation =
                evidenceGate.validate(List.of(evidence));

        if (!validation.valid()) {

            throw new IllegalStateException(
                    "Evidencia inválida: "
                            + String.join("; ", validation.errors())
            );
        }

        memory.registerCustomer(
                missionId,
                command.customerId(),
                command.name(),
                command.contact(),
                command.leadId(),
                evidence
        );

        return new CustomerResponse(
                command.customerId(),
                missionId,
                command.name(),
                Instant.now()
        );
    }

    public Optional<TransactionResponse> registerTransaction(
            String missionId,
            TransactionCommand command) {

        var evidence = new AgentResult.Evidence(
                command.evidenceDescription(),
                command.evidenceSource(),
                command.evidenceSourceType(),
                command.evidenceVerified()
        );

        var validation =
                evidenceGate.validate(List.of(evidence));

        if (!validation.valid()) {

            throw new IllegalStateException(
                    "Evidencia inválida: "
                            + String.join("; ", validation.errors())
            );
        }

        var recordedAt =
                memory.registerTransaction(
                        missionId,
                        command.customerId(),
                        command.transactionId(),
                        command.description(),
                        command.revenueUsd(),
                        command.costUsd(),
                        evidence
                );

        return recordedAt.map(timestamp -> new TransactionResponse(
                command.transactionId(),
                missionId,
                command.customerId(),
                command.revenueUsd(),
                command.costUsd(),
                command.revenueUsd() - command.costUsd(),
                Instant.parse(timestamp)
        ));
    }

    public MissionProfitResponse netProfit(String missionId) {

        var totals = memory.totalRevenueAndCost(missionId);
        var totalRevenue = totals[0];
        var totalCost = totals[1];
        var netProfit = totalRevenue - totalCost;
        var seedCapitalUsd = appProperties.seedCapitalUsd();

        var successCriterionMet = netProfit > seedCapitalUsd;

        return new MissionProfitResponse(
                missionId,
                totalRevenue,
                totalCost,
                netProfit,
                seedCapitalUsd,
                successCriterionMet,
                successLevel(netProfit)
        );
    }

    private String successLevel(double netProfit) {

        if (netProfit >= EXTRAORDINARY_THRESHOLD) {
            return "EXTRAORDINARIO";
        }

        if (netProfit >= EXCELLENT_THRESHOLD) {
            return "EXCELENTE";
        }

        if (netProfit > VERY_GOOD_THRESHOLD) {
            return "MUY_BUENO";
        }

        if (netProfit > GOOD_THRESHOLD) {
            return "BUENO";
        }

        return "NINGUNO";
    }
}
```

- [ ] **Step 6: Ejecutar `CustomerServiceTest` y confirmar que pasa**

Run: `cd app && mvn test -Dtest=CustomerServiceTest`
Expected: PASS (9/9: 7 existentes actualizados + 2 nuevos).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/CustomerCommand.java \
        app/src/main/java/com/aicompany/core/service/CustomerMemoryService.java \
        app/src/main/java/com/aicompany/core/service/CustomerService.java \
        app/src/test/java/com/aicompany/core/service/CustomerServiceTest.java
git commit -m "CustomerCommand.leadId dispara la conversión de un LEAD a cliente real"
```

---

### Task 3: API REST del lead (`LeadController`)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/DiscardLeadCommand.java`
- Create: `app/src/main/java/com/aicompany/core/controller/LeadController.java`
- Create: `app/src/test/java/com/aicompany/core/controller/LeadControllerTest.java`

**Interfaces:**
- Consumes: `OpportunityMemoryService.listLeads() -> List<LeadResponse>`, `OpportunityMemoryService.discardLead(String, String) -> Optional<LeadResponse>` (Task 1).

- [ ] **Step 1: Escribir el test que falla — `LeadControllerTest`**

```java
package com.aicompany.core.controller;

import com.aicompany.core.model.DiscardLeadCommand;
import com.aicompany.core.model.LeadResponse;
import com.aicompany.core.service.OpportunityMemoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeadControllerTest {

    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);

    private final LeadController controller = new LeadController(opportunityMemory);

    @Test
    void listDelegatesToOpportunityMemoryService() {
        var leads = List.of(new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                "Identificada en estudio de mercado", "https://example.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now()
        ));
        when(opportunityMemory.listLeads()).thenReturn(leads);

        assertEquals(leads, controller.list());
    }

    @Test
    void discardReturnsTheUpdatedLeadWhenSuccessful() {
        var updated = new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                "Identificada en estudio de mercado", "https://example.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now()
        );
        when(opportunityMemory.discardLead("MISSION-1-CANDIDATE-SALES-0", "No responde"))
                .thenReturn(Optional.of(updated));

        var response = controller.discard(
                "MISSION-1-CANDIDATE-SALES-0", new DiscardLeadCommand("No responde"));

        assertEquals(updated, response);
    }

    @Test
    void discardThrowsWhenLeadIsNotInLeadStatus() {
        when(opportunityMemory.discardLead("MISSION-404", "motivo"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> controller.discard("MISSION-404", new DiscardLeadCommand("motivo")));
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=LeadControllerTest`
Expected: FAIL — `LeadController`/`DiscardLeadCommand` no existen todavía (error de compilación).

- [ ] **Step 3: Crear `DiscardLeadCommand`**

```java
package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

public record DiscardLeadCommand(
        @NotBlank String reason
) {
}
```

- [ ] **Step 4: Crear `LeadController`**

```java
package com.aicompany.core.controller;

import com.aicompany.core.model.DiscardLeadCommand;
import com.aicompany.core.model.LeadResponse;
import com.aicompany.core.service.OpportunityMemoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Puente LEAD → cliente real (`empresa.md` §5): listar y descartar son
 * nivel 🟢 (visibilidad sobre datos ya generados por agentes); convertir
 * un LEAD en cliente real sigue siendo, a propósito, vía
 * {@code CustomerController.registerCustomer} (nivel 🔴, contacto/cierre
 * de venta reales) — este controller nunca crea un {@code Customer} real.
 */
@RestController
@RequestMapping("/api/company/leads")
public class LeadController {

    private final OpportunityMemoryService opportunityMemory;

    public LeadController(OpportunityMemoryService opportunityMemory) {
        this.opportunityMemory = opportunityMemory;
    }

    @GetMapping
    public List<LeadResponse> list() {
        return opportunityMemory.listLeads();
    }

    @PostMapping("/{leadId}/discard")
    public LeadResponse discard(
            @PathVariable String leadId,
            @Valid @RequestBody DiscardLeadCommand command) {

        return opportunityMemory.discardLead(leadId, command.reason())
                .orElseThrow(() -> new IllegalStateException(
                        "El lead " + leadId
                                + " no existe o ya no está en estado LEAD"
                ));
    }
}
```

- [ ] **Step 5: Ejecutar el test y confirmar que pasa**

Run: `cd app && mvn test -Dtest=LeadControllerTest`
Expected: PASS (3/3)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/DiscardLeadCommand.java \
        app/src/main/java/com/aicompany/core/controller/LeadController.java \
        app/src/test/java/com/aicompany/core/controller/LeadControllerTest.java
git commit -m "Agregar LeadController: GET /leads y POST /leads/{id}/discard"
```

---

### Task 4: Integración con el chat (`ChatIntentRouter` + `CeoService`)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `OpportunityMemoryService.listLeads() -> List<LeadResponse>` (Task 1, ya inyectado en `ChatIntentRouter` como `opportunityMemory`).

- [ ] **Step 1: Actualizar `ChatIntentRouterTest.java`**

Agregar el import al principio del archivo:

```java
import com.aicompany.core.model.LeadResponse;
```

Agregar un test nuevo (junto a `routesOpportunitiesQueryWithDeterministicFormatting`):

```java
    @Test
    void routesLeadsQueryWithDeterministicFormatting() {
        when(opportunityMemory.listLeads()).thenReturn(List.of(
                new LeadResponse(
                        "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                        "Identificada en estudio de mercado", "https://example.com", "WEB",
                        "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now()
                )
        ));

        var response = router.route("¿Qué leads tengo para contactar?");

        assertTrue(response.contains("1 lead"));
        assertTrue(response.contains("Panadería El Sol"));
        verifyNoInteractions(ceoService);
    }
```

En `passesCompanyMemoryQueryCallbackThatResolvesAllKnownTopics`, agregar el stub `when(opportunityMemory.listLeads()).thenReturn(List.of());` junto a los demás `when(...)` de esa prueba, y agregar esta aserción junto a la de `OPPORTUNITIES`:

```java
        assertTrue(companyMemoryQuery.apply("LEADS").contains("No hay ningún lead"));
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: FAIL — `QueryIntent.LEADS` y el topic `"LEADS"` no existen todavía (error de compilación/assertion).

- [ ] **Step 3: Agregar el import de `LeadResponse` a `ChatIntentRouter`**

Junto a los imports existentes:

```java
import com.aicompany.core.model.LeadResponse;
```

- [ ] **Step 4: Agregar `LEADS` al enum `QueryIntent` y a `detectQuery`**

Reemplazar el enum:

```java
    private enum QueryIntent {
        AGENT_STATUS,
        MISSIONS_NEEDING_ATTENTION,
        FAILED_MISSIONS,
        TEST_MISSIONS,
        OPPORTUNITIES,
        LEADS,
        COMPANY_PROFIT,
        COMPANY_STATUS
    }
```

Agregar el chequeo justo después del de `OPPORTUNITIES` en `detectQuery`:

```java
        if (normalized.contains("oportunidad")) {
            return QueryIntent.OPPORTUNITIES;
        }

        if (normalized.contains("lead")
                || normalized.contains("prospecto")
                || normalized.contains("a quien contacto")) {
            return QueryIntent.LEADS;
        }
```

- [ ] **Step 5: Agregar el caso `LEADS` a `answerMemoryTopic` y el formatter**

En el `switch` de `answerMemoryTopic`, agregar la línea justo después del caso `OPPORTUNITIES`:

```java
            case "LEADS" -> formatLeads(opportunityMemory.listLeads());
```

Agregar el método nuevo justo después de `formatOpportunities`:

```java
    private String formatLeads(List<LeadResponse> leads) {

        if (leads.isEmpty()) {
            return "No hay ningún lead activo para contactar.";
        }

        var lines = leads.stream()
                .map(l -> l.id() + " (misión " + l.missionId() + "): "
                        + l.name() + " — " + l.description())
                .collect(Collectors.joining(" | "));

        return "Tenés " + leads.size()
                + " lead(s) activo(s) para contactar: " + lines;
    }
```

- [ ] **Step 6: Agregar `LEADS` al enum `topic` de `CeoService`**

En `COMPANY_MEMORY_TOOLS`, agregar `"LEADS"` a la lista del `enum` (justo después de `"OPPORTUNITIES"`):

```java
                                                    "enum", List.of(
                                                            "AGENT_STATUS",
                                                            "MISSIONS_NEEDING_ATTENTION",
                                                            "FAILED_MISSIONS",
                                                            "TEST_MISSIONS",
                                                            "LAST_MENTIONED",
                                                            "OPPORTUNITIES",
                                                            "LEADS",
                                                            "COMPANY_PROFIT",
                                                            "COMPANY_STATUS"
                                                    ),
```

Y agregar la frase descriptiva correspondiente en la `description` del parámetro, justo después del tramo de `OPPORTUNITIES`:

```java
                                                            + "OPPORTUNITIES: "
                                                            + "oportunidades "
                                                            + "identificadas. "
                                                            + "LEADS: "
                                                            + "candidatos de "
                                                            + "cliente (LEAD) "
                                                            + "que un agente "
                                                            + "identificó y "
                                                            + "todavía no se "
                                                            + "contactaron ni "
                                                            + "convirtieron. "
                                                            + "COMPANY_PROFIT: "
```

(Reemplaza el tramo `"OPPORTUNITIES: " + ... + "COMPANY_PROFIT: "` ya existente por esta versión con `LEADS` insertado en el medio — el resto de la cadena de concatenación no cambia.)

- [ ] **Step 7: Ejecutar `ChatIntentRouterTest` y confirmar que pasa**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: PASS (todos los tests, incluido el nuevo).

- [ ] **Step 8: Correr toda la suite**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, todos los tests pasan.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/main/java/com/aicompany/core/service/CeoService.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Agregar intent LEADS al chat: qué candidatos hay para contactar"
```

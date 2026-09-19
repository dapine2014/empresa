# Chat con prospectos reales por misión (Sub-proyecto A) — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el chat del Command Center muestre los prospectos reales (`Customer{status:'LEAD'}`) de una misión puntual, ordenados por probabilidad, y que un follow-up tipo "contactalo" resuelva contra el foco conversacional — sin inventar nunca un dato de contacto.

**Architecture:** Todo el trabajo es una extensión del router determinista ya existente (`ChatIntentRouter`) y de la capa de persistencia ya existente (`OpportunityMemoryService`) — mismo patrón arquitectónico que `resolveMissionDetailsQuery`/`handleReference` (chequeos deterministas en `resolve()`, nunca el modelo decidiendo la ruta). El agente autoreporta un nuevo campo `confidence` en `AgentResult.CustomerCandidate`; ese valor se persiste en el nodo `Customer` y se usa para ordenar y para resolver referencias ambiguas.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Neo4j (driver plano, Cypher a mano), JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-18-prospect-chat-grounding-design.md`

## Global Constraints

- Todo el routing sigue siendo 100% determinista (regex/keywords en Java) — nunca se le pide al LLM que decida a qué misión/prospecto se refiere un mensaje.
- `confidence` es autoreportado por el agente (double, 0.0–1.0), nunca calculado por el sistema.
- Esta ronda **nunca** inventa ni expone un dato de contacto real (teléfono/email) — es puramente informativa.
- `OpportunityMemoryService` sigue el patrón `*MemoryService` del proyecto: pura I/O de Neo4j, sin tests unitarios directos, sin lanzar excepciones (devuelve `Optional`/lista vacía).
- Sin `MISSION-<id>` explícito, el comportamiento global de `OPPORTUNITIES` (lista de las 20 más recientes, sin prospectos) no cambia — regresión cubierta por el test ya existente.
- `mvn test` debe quedar en verde después de cada tarea.

---

### Task 1: `AgentResult.CustomerCandidate` gana `confidence`; el schema lo exige

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/AgentResult.java:86-92`
- Modify: `app/src/main/java/com/aicompany/core/agent/model/AgentResultSchema.java:69-87`
- Modify: `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java:71-76`
- Create: `app/src/test/java/com/aicompany/core/agent/model/AgentResultSchemaTest.java`

**Interfaces:**
- Produces: `AgentResult.CustomerCandidate(String name, String description, String source, String sourceType, double confidence)` — el 5º campo que usan las Tasks 2 y 3.
- Produces: `AgentResultSchema.SCHEMA` con `customerCandidates.items.required` incluyendo `"confidence"` y `customerCandidates.items.properties.confidence = {"type":"number"}`.

- [ ] **Step 1: Escribir el test que falla**

Crear `app/src/test/java/com/aicompany/core/agent/model/AgentResultSchemaTest.java`:

```java
package com.aicompany.core.agent.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResultSchemaTest {

    @SuppressWarnings("unchecked")
    @Test
    void customerCandidateItemRequiresConfidenceAsANumber() {
        var properties = (Map<String, Object>) AgentResultSchema.SCHEMA.get("properties");
        var customerCandidatesProperty = (Map<String, Object>) properties.get("customerCandidates");
        var customerCandidateItem = (Map<String, Object>) customerCandidatesProperty.get("items");

        var itemProperties = (Map<String, Object>) customerCandidateItem.get("properties");
        var required = (List<String>) customerCandidateItem.get("required");

        assertTrue(required.contains("confidence"));
        assertEquals(Map.of("type", "number"), itemProperties.get("confidence"));
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=AgentResultSchemaTest`
Expected: FAIL — `required.contains("confidence")` es `false` (el schema todavía no lo tiene).

- [ ] **Step 3: Agregar `confidence` al record `CustomerCandidate`**

En `app/src/main/java/com/aicompany/core/AgentResult.java`, reemplazar (líneas 86-92):

```java
    public record CustomerCandidate(
            String name,
            String description,
            String source,
            String sourceType
    ) {
    }
```

por:

```java
    public record CustomerCandidate(
            String name,
            String description,
            String source,
            String sourceType,
            double confidence
    ) {
    }
```

- [ ] **Step 4: Agregar `confidence` al schema**

En `app/src/main/java/com/aicompany/core/agent/model/AgentResultSchema.java`, reemplazar el bloque `CUSTOMER_CANDIDATE_ITEM_SCHEMA` (líneas 69-87):

```java
    private static final Map<String, Object> CUSTOMER_CANDIDATE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of("type", "string", "minLength", 1),
                    "description", Map.of("type", "string", "minLength", 1),
                    "source", Map.of("type", "string"),
                    "sourceType", Map.of(
                            "type", "string",
                            "enum", EVIDENCE_SOURCE_TYPES
                    )
            ),
            "required", List.of(
                    "name",
                    "description",
                    "source",
                    "sourceType"
            ),
            "additionalProperties", false
    );
```

por:

```java
    private static final Map<String, Object> CUSTOMER_CANDIDATE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of("type", "string", "minLength", 1),
                    "description", Map.of("type", "string", "minLength", 1),
                    "source", Map.of("type", "string"),
                    "sourceType", Map.of(
                            "type", "string",
                            "enum", EVIDENCE_SOURCE_TYPES
                    ),
                    "confidence", Map.of("type", "number")
            ),
            "required", List.of(
                    "name",
                    "description",
                    "source",
                    "sourceType",
                    "confidence"
            ),
            "additionalProperties", false
    );
```

- [ ] **Step 5: Arreglar el único call-site que rompe compilación**

En `app/src/test/java/com/aicompany/core/service/MissionExecutorTest.java`, reemplazar (líneas 71-76):

```java
        var candidate = new AgentResult.CustomerCandidate(
                "Microempresas de logística en Medellín",
                "Identificadas en estudios de mercado citados por el agente",
                "https://example.com/estudio-logistica",
                "WEB"
        );
```

por:

```java
        var candidate = new AgentResult.CustomerCandidate(
                "Microempresas de logística en Medellín",
                "Identificadas en estudios de mercado citados por el agente",
                "https://example.com/estudio-logistica",
                "WEB",
                0.4
        );
```

- [ ] **Step 6: Correr toda la suite y confirmar que pasa**

Run: `cd app && mvn test`
Expected: PASS (incluyendo `AgentResultSchemaTest` y `MissionExecutorTest`).

- [ ] **Step 7: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/AgentResult.java \
  src/main/java/com/aicompany/core/agent/model/AgentResultSchema.java \
  src/test/java/com/aicompany/core/service/MissionExecutorTest.java \
  src/test/java/com/aicompany/core/agent/model/AgentResultSchemaTest.java
git commit -m "CustomerCandidate gana confidence, exigido por el schema"
```

---

### Task 2: El prompt del agente instruye sobre `confidence`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/AgentRuntime.java:516-521` (bloque REGLAS)
- Modify: `app/src/main/java/com/aicompany/core/agent/AgentRuntime.java:569-576` (plantilla `CUSTOMER_CANDIDATE`)

**Interfaces:**
- Consumes: nada nuevo de otra tarea (es solo texto de prompt).
- Produces: nada que otra tarea consuma en código — es contenido de prompt, no una API.

Sin test automatizado: el contenido de un prompt no es testeable de forma determinista (no hay ningún test existente sobre `buildPrompt`, es un método privado que arma texto libre para el modelo — mismo criterio ya documentado en el spec del Sub-proyecto B). La verificación es de compilación + suite existente en verde, y verificación en vivo más adelante.

- [ ] **Step 1: Agregar la instrucción de `confidence` a REGLAS**

En `app/src/main/java/com/aicompany/core/agent/AgentRuntime.java`, ubicar el bloque (líneas 516-521):

```java
                - customerCandidates es para perfiles de clientes
                  concretos y reales que hayas identificado en tu
                  investigación (no genéricos como "microempresarios en
                  general") — nunca un cliente real ni contactado, eso
                  solo lo registra un humano. Si no identificaste ninguno,
                  déjalo como lista vacía; no inventes uno para llenarlo.
```

y agregar inmediatamente después (mismo nivel de indentación, dentro del bloque de texto):

```java
                - confidence de cada customerCandidate: alto (mayor
                  a 0.7) solo si es una empresa nombrada, real y
                  verificable con una fuente específica de esa
                  empresa; bajo (menor a 0.4) si en realidad es un
                  segmento de mercado genérico. Nunca reportes
                  confidence alto solo porque el nombre suena a
                  empresa real.
```

- [ ] **Step 2: Agregar `confidence` a la plantilla `CUSTOMER_CANDIDATE`**

En el mismo archivo, ubicar (líneas 569-576):

```java
                CUSTOMER_CANDIDATE:

                {
                  "name": "",
                  "description": "",
                  "source": "",
                  "sourceType": "WEB|CUSTOMER|TRANSACTION|INTERNAL|NONE"
                }
```

reemplazar por:

```java
                CUSTOMER_CANDIDATE:

                {
                  "name": "",
                  "description": "",
                  "source": "",
                  "sourceType": "WEB|CUSTOMER|TRANSACTION|INTERNAL|NONE",
                  "confidence": 0.0
                }
```

- [ ] **Step 3: Correr la suite y confirmar que sigue en verde**

Run: `cd app && mvn test`
Expected: PASS (nada de esto tiene test directo, pero no debe romper nada existente).

- [ ] **Step 4: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/agent/AgentRuntime.java
git commit -m "Prompt del agente instruye confidence honesto para customerCandidates"
```

---

### Task 3: `LeadResponse` expone `confidence`; `OpportunityMemoryService` lo persiste y lo devuelve

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/LeadResponse.java`
- Modify: `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java:82-133` (`recordCandidate`)
- Modify: `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java:164-188` (`listLeads`)
- Modify: `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java:202-238` (`discardLead`)
- Modify: `app/src/test/java/com/aicompany/core/controller/LeadControllerTest.java:25-30,38-43`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java:393-398`

**Interfaces:**
- Consumes: `AgentResult.CustomerCandidate.confidence()` (Task 1).
- Produces: `LeadResponse(String id, String name, String description, String source, String sourceType, String missionId, String opportunityId, Instant createdAt, String status, String discardReason, Instant discardedAt, double confidence)` — el 12º campo que usan las Tasks 4 y 5.

Sin test unitario nuevo para `OpportunityMemoryService` (convención del proyecto: los `*MemoryService` son integración Neo4j pura, sin test directo). La verificación es compilación + los tests existentes de `LeadControllerTest`/`ChatIntentRouterTest` actualizados, que sí compilan y pasan contra el nuevo constructor.

- [ ] **Step 1: Agregar `confidence` a `LeadResponse`**

Reemplazar el contenido completo de `app/src/main/java/com/aicompany/core/model/LeadResponse.java`:

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
        Instant createdAt,
        String status,
        String discardReason,
        Instant discardedAt,
        double confidence
) {
}
```

- [ ] **Step 2: Arreglar los 3 call-sites que rompen compilación (sin tocar comportamiento todavía)**

En `app/src/test/java/com/aicompany/core/controller/LeadControllerTest.java`, reemplazar (líneas 25-30):

```java
        var leads = List.of(new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                "Identificada en estudio de mercado", "https://example.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                "LEAD", null, null
        ));
```

por:

```java
        var leads = List.of(new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                "Identificada en estudio de mercado", "https://example.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                "LEAD", null, null, 0.8
        ));
```

y (líneas 38-43):

```java
        var updated = new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                "Identificada en estudio de mercado", "https://example.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                "DESCARTADO", "No responde", Instant.now()
        );
```

por:

```java
        var updated = new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                "Identificada en estudio de mercado", "https://example.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                "DESCARTADO", "No responde", Instant.now(), 0.8
        );
```

En `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`, reemplazar (líneas 393-398):

```java
                new LeadResponse(
                        "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                        "Identificada en estudio de mercado", "https://example.com", "WEB",
                        "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                        "LEAD", null, null
                )
```

por:

```java
                new LeadResponse(
                        "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                        "Identificada en estudio de mercado", "https://example.com", "WEB",
                        "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                        "LEAD", null, null, 0.8
                )
```

- [ ] **Step 3: Correr la suite y confirmar que vuelve a compilar y pasar**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 4: Persistir `confidence` en `recordCandidate`**

En `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java`, reemplazar el primer `tx.run(...)` de `recordCandidate` (líneas 96-110):

```java
                tx.run("MATCH (o:Opportunity {id:$opportunityId}) " +
                                "MERGE (c:Customer {id:$candidateId}) " +
                                "ON CREATE SET c.missionId=$missionId, " +
                                "c.name=$name, c.status='LEAD', " +
                                "c.identifiedByAgent=$agentId, c.createdAt=$now " +
                                "SET c.updatedAt=$now " +
                                "MERGE (o)-[:HAS_CANDIDATE]->(c)",
                        Map.of(
                                "opportunityId", missionId + "-OPPORTUNITY",
                                "candidateId", candidateId,
                                "missionId", missionId,
                                "name", candidate.name() == null ? "" : candidate.name(),
                                "agentId", agentId,
                                "now", now
                        ));
```

por:

```java
                tx.run("MATCH (o:Opportunity {id:$opportunityId}) " +
                                "MERGE (c:Customer {id:$candidateId}) " +
                                "ON CREATE SET c.missionId=$missionId, " +
                                "c.name=$name, c.status='LEAD', " +
                                "c.identifiedByAgent=$agentId, c.createdAt=$now, " +
                                "c.confidence=$confidence " +
                                "SET c.updatedAt=$now " +
                                "MERGE (o)-[:HAS_CANDIDATE]->(c)",
                        Map.of(
                                "opportunityId", missionId + "-OPPORTUNITY",
                                "candidateId", candidateId,
                                "missionId", missionId,
                                "name", candidate.name() == null ? "" : candidate.name(),
                                "agentId", agentId,
                                "confidence", candidate.confidence(),
                                "now", now
                        ));
```

- [ ] **Step 5: Devolver `confidence` en `listLeads`**

Reemplazar el método completo `listLeads()` (líneas 164-188):

```java
    public List<LeadResponse> listLeads() {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (o:Opportunity)-[:HAS_CANDIDATE]->(c:Customer {status:'LEAD'}) " +
                                    "OPTIONAL MATCH (c)-[:HAS_EVIDENCE]->(e:Evidence) " +
                                    "RETURN c.id AS id, c.name AS name, c.missionId AS missionId, " +
                                    "o.id AS opportunityId, c.createdAt AS createdAt, " +
                                    "e.description AS description, e.source AS source, " +
                                    "e.sourceType AS sourceType, c.status AS status " +
                                    "ORDER BY c.createdAt DESC")
                    .list(r -> new LeadResponse(
                            r.get("id").asString(),
                            r.get("name").asString(""),
                            r.get("description").asString(""),
                            r.get("source").asString(""),
                            r.get("sourceType").asString(""),
                            r.get("missionId").asString(),
                            r.get("opportunityId").asString(),
                            Instant.parse(r.get("createdAt").asString()),
                            r.get("status").asString("LEAD"),
                            null,
                            null
                    ));
        }
    }
```

por:

```java
    public List<LeadResponse> listLeads() {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (o:Opportunity)-[:HAS_CANDIDATE]->(c:Customer {status:'LEAD'}) " +
                                    "OPTIONAL MATCH (c)-[:HAS_EVIDENCE]->(e:Evidence) " +
                                    "RETURN c.id AS id, c.name AS name, c.missionId AS missionId, " +
                                    "o.id AS opportunityId, c.createdAt AS createdAt, " +
                                    "e.description AS description, e.source AS source, " +
                                    "e.sourceType AS sourceType, c.status AS status, " +
                                    "coalesce(c.confidence, 0.0) AS confidence " +
                                    "ORDER BY c.createdAt DESC")
                    .list(r -> new LeadResponse(
                            r.get("id").asString(),
                            r.get("name").asString(""),
                            r.get("description").asString(""),
                            r.get("source").asString(""),
                            r.get("sourceType").asString(""),
                            r.get("missionId").asString(),
                            r.get("opportunityId").asString(),
                            Instant.parse(r.get("createdAt").asString()),
                            r.get("status").asString("LEAD"),
                            null,
                            null,
                            r.get("confidence").asDouble(0.0)
                    ));
        }
    }
```

- [ ] **Step 6: Devolver `confidence` en `discardLead`**

Reemplazar el método completo `discardLead(...)` (líneas 202-238):

```java
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
                                "e.sourceType AS sourceType, c.status AS status, " +
                                "c.discardReason AS discardReason, c.discardedAt AS discardedAt",
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
                        Instant.parse(r.get("createdAt").asString()),
                        r.get("status").asString("DESCARTADO"),
                        r.get("discardReason").asString(""),
                        Instant.parse(r.get("discardedAt").asString())
                ));
            });
        }
    }
```

por:

```java
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
                                "e.sourceType AS sourceType, c.status AS status, " +
                                "c.discardReason AS discardReason, c.discardedAt AS discardedAt, " +
                                "coalesce(c.confidence, 0.0) AS confidence",
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
                        Instant.parse(r.get("createdAt").asString()),
                        r.get("status").asString("DESCARTADO"),
                        r.get("discardReason").asString(""),
                        Instant.parse(r.get("discardedAt").asString()),
                        r.get("confidence").asDouble(0.0)
                ));
            });
        }
    }
```

- [ ] **Step 7: Correr toda la suite y confirmar que pasa**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/model/LeadResponse.java \
  src/main/java/com/aicompany/core/service/OpportunityMemoryService.java \
  src/test/java/com/aicompany/core/controller/LeadControllerTest.java \
  src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "LeadResponse expone confidence; OpportunityMemoryService lo persiste y devuelve"
```

---

### Task 4: "oportunidades de MISSION-X" trae los prospectos reales ordenados por probabilidad

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java` (agregar 2 métodos nuevos, al final de la clase antes de `listRecent` o después — ver Step 1)
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `LeadResponse.confidence()` (Task 3).
- Produces: `OpportunityMemoryService.findByMissionId(String missionId) -> Optional<OpportunitySummary>`, `OpportunityMemoryService.listCandidatesForMission(String missionId) -> List<LeadResponse>` (ordenada por `confidence` descendente) — usados por `ChatIntentRouter`. `ChatIntentRouter` gana `resolveOpportunityForMissionQuery`/`handleOpportunityForMissionQuery`/`formatOpportunityWithCandidates` (privados) y setea el foco conversacional `type="CUSTOMER"` con los ids en el mismo orden — Task 5 lo consume.

- [ ] **Step 1: Agregar los 2 métodos nuevos a `OpportunityMemoryService`**

En `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java`, agregar estos dos métodos nuevos justo antes del método `listRecent` (al final de la clase, antes de la llave de cierre):

```java
    /**
     * La Opportunity real de una misión puntual — a diferencia de
     * {@link #listRecent}, que trae una lista global sin filtrar. Usada
     * por el chat cuando el usuario menciona un {@code MISSION-<id>}
     * explícito junto con la palabra "oportunidad".
     */
    public Optional<OpportunitySummary> findByMissionId(String missionId) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (o:Opportunity {missionId:$missionId}) " +
                                    "RETURN o.id AS id, o.missionId AS missionId, " +
                                    "o.description AS description, o.status AS status, " +
                                    "o.createdAt AS createdAt",
                            Map.of("missionId", missionId))
                    .list(r -> new OpportunitySummary(
                            r.get("id").asString(),
                            r.get("missionId").asString(),
                            r.get("description").asString(),
                            r.get("status").asString(),
                            Instant.parse(r.get("createdAt").asString())
                    ))
                    .stream()
                    .findFirst();
        }
    }

    /**
     * Prospectos reales ({@code Customer{status:'LEAD'}}) de la
     * Opportunity de una misión puntual, ordenados por {@code confidence}
     * descendente — el agente que los identificó autoreporta ese valor
     * (ver {@link #recordCandidate}).
     */
    public List<LeadResponse> listCandidatesForMission(String missionId) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (o:Opportunity {missionId:$missionId})-[:HAS_CANDIDATE]->(c:Customer {status:'LEAD'}) " +
                                    "OPTIONAL MATCH (c)-[:HAS_EVIDENCE]->(e:Evidence) " +
                                    "RETURN c.id AS id, c.name AS name, c.missionId AS missionId, " +
                                    "o.id AS opportunityId, c.createdAt AS createdAt, " +
                                    "e.description AS description, e.source AS source, " +
                                    "e.sourceType AS sourceType, c.status AS status, " +
                                    "coalesce(c.confidence, 0.0) AS confidence " +
                                    "ORDER BY c.confidence DESC",
                            Map.of("missionId", missionId))
                    .list(r -> new LeadResponse(
                            r.get("id").asString(),
                            r.get("name").asString(""),
                            r.get("description").asString(""),
                            r.get("source").asString(""),
                            r.get("sourceType").asString(""),
                            r.get("missionId").asString(),
                            r.get("opportunityId").asString(),
                            Instant.parse(r.get("createdAt").asString()),
                            r.get("status").asString("LEAD"),
                            null,
                            null,
                            r.get("confidence").asDouble(0.0)
                    ));
        }
    }
```

- [ ] **Step 2: Escribir los tests que fallan en `ChatIntentRouterTest`**

Agregar estos 2 tests nuevos a `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java` (por ejemplo, justo después de `routesOpportunitiesQueryWithDeterministicFormatting`):

```java
    @Test
    void routesOpportunitiesQueryWithMissionIdBringsCandidatesOrderedByConfidence() {
        when(opportunityMemory.findByMissionId("MISSION-1")).thenReturn(Optional.of(
                new OpportunitySummary("MISSION-1-OPPORTUNITY", "MISSION-1", "asesoría a microempresas", "IDENTIFIED", Instant.now())
        ));
        when(opportunityMemory.listCandidatesForMission("MISSION-1")).thenReturn(List.of(
                new LeadResponse(
                        "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                        "Identificada en estudio de mercado", "https://example.com", "WEB",
                        "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                        "LEAD", null, null, 0.8
                )
        ));

        var response = router.route(
                "¿Qué oportunidades concretas tenemos en la misión MISSION-1 y qué prospectos reales están asociados?");

        assertTrue(response.contains("asesoría a microempresas"));
        assertTrue(response.contains("Panadería El Sol"));
        assertTrue(response.contains("0.80"));
        verify(conversationMemory).setLastMentioned("CUSTOMER", List.of("MISSION-1-CANDIDATE-SALES-0"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void routesOpportunitiesQueryWithMissionIdThatHasNoOpportunityYet() {
        when(opportunityMemory.findByMissionId("MISSION-404")).thenReturn(Optional.empty());

        var response = router.route("¿Qué oportunidades tenemos en la misión MISSION-404?");

        assertTrue(response.contains("No encontré ninguna oportunidad"));
        verifyNoInteractions(ceoService);
    }
```

- [ ] **Step 3: Correr los tests nuevos y confirmar que fallan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest#routesOpportunitiesQueryWithMissionIdBringsCandidatesOrderedByConfidence,ChatIntentRouterTest#routesOpportunitiesQueryWithMissionIdThatHasNoOpportunityYet`
Expected: FAIL — el router todavía no distingue un `MISSION-<id>` explícito en una consulta de oportunidades, cae al camino global (`listRecent`, no stubbeado → lista vacía → "Todavía no hay ninguna oportunidad identificada.").

- [ ] **Step 4: Implementar la resolución en `ChatIntentRouter`**

En `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`, agregar estos 3 métodos privados nuevos justo después de `formatMissionDetails` (después de la línea 392, antes de `private enum ReferencePredicate`):

```java
    /**
     * "¿qué oportunidades concretas tenemos en la misión MISSION-X y qué
     * prospectos reales están asociados?" caía en la consulta global
     * {@code OPPORTUNITIES} (ignorando el {@code MISSION-<id>} del
     * mensaje) y nunca mostraba los prospectos reales, pese a que sí
     * existen como {@code Customer{status:'LEAD'}} enlazados vía
     * {@code HAS_CANDIDATE} (reportado por el usuario). Mismo criterio
     * que {@link #resolveMissionDetailsQuery}: solo actúa con un
     * {@code MISSION-<id>} explícito en el mensaje — sin uno, el
     * comportamiento global de {@code OPPORTUNITIES} (lista de las 20
     * más recientes, sin prospectos) no cambia.
     */
    private Optional<String> resolveOpportunityForMissionQuery(String message) {

        var normalized = normalize(message);

        if (!normalized.contains("oportunidad")) {
            return Optional.empty();
        }

        var missionIdMatcher = MISSION_ID.matcher(message);

        if (!missionIdMatcher.find()) {
            return Optional.empty();
        }

        return Optional.of(missionIdMatcher.group(1).toUpperCase(Locale.ROOT));
    }

    private String handleOpportunityForMissionQuery(String missionId) {

        log.info("CHAT_INTENT_OPPORTUNITY_DETAILS missionId={}", missionId);

        var opportunity = opportunityMemory.findByMissionId(missionId);

        if (opportunity.isEmpty()) {
            return "No encontré ninguna oportunidad para " + missionId + ".";
        }

        var candidates = opportunityMemory.listCandidatesForMission(missionId);

        conversationMemory.setLastMentioned(
                "CUSTOMER",
                candidates.stream().map(LeadResponse::id).toList()
        );

        return formatOpportunityWithCandidates(opportunity.get(), candidates);
    }

    private String formatOpportunityWithCandidates(
            OpportunitySummary opportunity,
            List<LeadResponse> candidates) {

        var header = "Oportunidad " + opportunity.id() + " (misión " + opportunity.missionId()
                + ", estado " + opportunity.status() + "): " + opportunity.description();

        if (candidates.isEmpty()) {
            return header + " Todavía no hay ningún prospecto real identificado para esta oportunidad.";
        }

        var lines = candidates.stream()
                .map(c -> c.name() + " (confidence="
                        + String.format(Locale.ROOT, "%.2f", c.confidence())
                        + ", fuente: " + c.source() + "): " + c.description())
                .collect(Collectors.joining(" | "));

        return header + " Prospectos reales identificados (" + candidates.size()
                + "), ordenados por probabilidad: " + lines;
    }
```

Luego, en el método `resolve(String message)`, ubicar este bloque (justo después de `handleDecision`, antes de `resolveMissionDetailsQuery`):

```java
        var missionDetailsId = resolveMissionDetailsQuery(message);

        if (missionDetailsId.isPresent()) {
            return handleMissionDetailsQuery(missionDetailsId.get());
        }
```

y reemplazarlo por:

```java
        var opportunityForMissionId = resolveOpportunityForMissionQuery(message);

        if (opportunityForMissionId.isPresent()) {
            return handleOpportunityForMissionQuery(opportunityForMissionId.get());
        }

        var missionDetailsId = resolveMissionDetailsQuery(message);

        if (missionDetailsId.isPresent()) {
            return handleMissionDetailsQuery(missionDetailsId.get());
        }
```

- [ ] **Step 5: Correr los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: PASS (los 2 nuevos y la regresión `routesOpportunitiesQueryWithDeterministicFormatting`, que sigue sin `MISSION-<id>` en el mensaje).

- [ ] **Step 6: Correr toda la suite**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/service/OpportunityMemoryService.java \
  src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
  src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: oportunidades de una mision puntual traen los prospectos reales ordenados por confidence"
```

---

### Task 5: "contactalo" resuelve contra el foco conversacional de prospectos

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java` (agregar 1 método nuevo)
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `conversationMemory.lastMentioned()` (ya existente, foco `type="CUSTOMER"` seteado por la Task 4), `LeadResponse` (Task 3).
- Produces: `OpportunityMemoryService.findCandidatesByIds(List<String> ids) -> List<LeadResponse>` (sin orden garantizado — el orden de probabilidad ya vive en la lista de ids del foco, no en esta consulta).

- [ ] **Step 1: Agregar el método nuevo a `OpportunityMemoryService`**

En `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java`, agregar este método al final de la clase (después de `listCandidatesForMission`, antes de `listRecent`):

```java
    /**
     * Prospectos puntuales por id — usado para resolver una referencia
     * conversacional contra el foco {@code type="CUSTOMER"} (ver
     * {@code ChatIntentRouter.handleCustomerReference}), siempre contra
     * el dato real y actual en Neo4j, nunca contra el texto de una
     * respuesta anterior.
     */
    public List<LeadResponse> findCandidatesByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (o:Opportunity)-[:HAS_CANDIDATE]->(c:Customer) WHERE c.id IN $ids " +
                                    "OPTIONAL MATCH (c)-[:HAS_EVIDENCE]->(e:Evidence) " +
                                    "RETURN c.id AS id, c.name AS name, c.missionId AS missionId, " +
                                    "o.id AS opportunityId, c.createdAt AS createdAt, " +
                                    "e.description AS description, e.source AS source, " +
                                    "e.sourceType AS sourceType, c.status AS status, " +
                                    "coalesce(c.confidence, 0.0) AS confidence",
                            Map.of("ids", ids))
                    .list(r -> new LeadResponse(
                            r.get("id").asString(),
                            r.get("name").asString(""),
                            r.get("description").asString(""),
                            r.get("source").asString(""),
                            r.get("sourceType").asString(""),
                            r.get("missionId").asString(),
                            r.get("opportunityId").asString(),
                            Instant.parse(r.get("createdAt").asString()),
                            r.get("status").asString(""),
                            null,
                            null,
                            r.get("confidence").asDouble(0.0)
                    ));
        }
    }
```

- [ ] **Step 2: Escribir los tests que fallan en `ChatIntentRouterTest`**

Agregar estos 4 tests nuevos (por ejemplo, después de los 2 agregados en la Task 4):

```java
    @Test
    void customerReferenceResolvesToTheSingleProspectInFocus() {
        when(conversationMemory.lastMentioned()).thenReturn(Optional.of(
                new LastMentioned("CUSTOMER", List.of("MISSION-1-CANDIDATE-SALES-0"))
        ));
        when(opportunityMemory.findCandidatesByIds(List.of("MISSION-1-CANDIDATE-SALES-0"))).thenReturn(List.of(
                new LeadResponse(
                        "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                        "Identificada en estudio de mercado", "https://example.com", "WEB",
                        "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                        "LEAD", null, null, 0.8
                )
        ));

        var response = router.route("Contactalo por favor.");

        assertTrue(response.contains("Panadería El Sol"));
        assertFalse(response.contains("Avisame"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void customerReferenceResolvesToTheHighestConfidenceProspectWhenSeveralAndNoneMentioned() {
        when(conversationMemory.lastMentioned()).thenReturn(Optional.of(
                new LastMentioned("CUSTOMER", List.of("MISSION-1-CANDIDATE-SALES-0", "MISSION-1-CANDIDATE-SALES-1"))
        ));
        when(opportunityMemory.findCandidatesByIds(List.of("MISSION-1-CANDIDATE-SALES-0", "MISSION-1-CANDIDATE-SALES-1")))
                .thenReturn(List.of(
                        new LeadResponse(
                                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                                "Identificada en estudio de mercado", "https://example.com", "WEB",
                                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                                "LEAD", null, null, 0.8
                        ),
                        new LeadResponse(
                                "MISSION-1-CANDIDATE-SALES-1", "Estudio PixelCraft",
                                "Identificado en artículo de tendencias", "https://example.com/2", "WEB",
                                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                                "LEAD", null, null, 0.3
                        )
                ));

        var response = router.route("Contactalo.");

        assertTrue(response.contains("Panadería El Sol"));
        assertTrue(response.contains("Avisame"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void customerReferenceResolvesToTheExplicitlyNamedProspectAmongSeveral() {
        when(conversationMemory.lastMentioned()).thenReturn(Optional.of(
                new LastMentioned("CUSTOMER", List.of("MISSION-1-CANDIDATE-SALES-0", "MISSION-1-CANDIDATE-SALES-1"))
        ));
        when(opportunityMemory.findCandidatesByIds(List.of("MISSION-1-CANDIDATE-SALES-0", "MISSION-1-CANDIDATE-SALES-1")))
                .thenReturn(List.of(
                        new LeadResponse(
                                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                                "Identificada en estudio de mercado", "https://example.com", "WEB",
                                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                                "LEAD", null, null, 0.8
                        ),
                        new LeadResponse(
                                "MISSION-1-CANDIDATE-SALES-1", "Estudio PixelCraft",
                                "Identificado en artículo de tendencias", "https://example.com/2", "WEB",
                                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                                "LEAD", null, null, 0.3
                        )
                ));

        var response = router.route("Contacta a Estudio PixelCraft.");

        assertTrue(response.contains("Estudio PixelCraft"));
        assertFalse(response.contains("Panadería El Sol"));
        verifyNoInteractions(ceoService);
    }

    @Test
    void customerReferenceFallsBackToGeneralChatWhenNoCustomerFocus() {
        when(conversationMemory.lastMentioned()).thenReturn(Optional.empty());
        when(companyMemory.agentName("ceo")).thenReturn(Optional.of("Alex"));
        when(companyMemory.teamRosterDescription()).thenReturn("- Sofia (Sales)");
        when(ceoService.chat(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn("respuesta general");

        var response = router.route("Contactalo por favor.");

        assertEquals("respuesta general", response);
        verify(opportunityMemory, never()).findCandidatesByIds(any());
    }
```

- [ ] **Step 3: Correr los tests nuevos y confirmar que fallan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: los 4 tests nuevos de esta tarea FAIL (el mensaje "contactalo" hoy cae al chat general siempre, sin importar el foco).

- [ ] **Step 4: Implementar la resolución en `ChatIntentRouter`**

Agregar estos 3 métodos privados nuevos, justo después de `formatOpportunityWithCandidates` (agregado en la Task 4) y antes de `private enum ReferencePredicate`:

```java
    /**
     * "contactalo"/"el contacto de X" caía al chat general, que
     * respondía que no tenía acceso a datos personales — técnicamente
     * cierto (nadie se los dio), pero el chat nunca intentó buscarlos
     * en Neo4j, donde sí existen como {@code Customer{status:'LEAD'}}
     * (reportado por el usuario). Mismo criterio que
     * {@link #handleReference} (foco {@code type="MISSION"}), pero para
     * el foco {@code type="CUSTOMER"} que arma
     * {@link #handleOpportunityForMissionQuery}. Sin un foco
     * {@code CUSTOMER} vigente no hay nada que resolver — nunca se
     * adivina, cae al chat general.
     */
    private Optional<List<String>> resolveCustomerReference(String message) {

        var normalized = normalize(message);

        if (!normalized.contains("contact")) {
            return Optional.empty();
        }

        return conversationMemory.lastMentioned()
                .filter(focus -> "CUSTOMER".equals(focus.type()) && !focus.ids().isEmpty())
                .map(focus -> focus.ids());
    }

    /**
     * Nunca inventa un teléfono/email: esta ronda es puramente
     * informativa (no hay integración de telefonía/email construida
     * todavía) — solo muestra lo que existe de verdad y aclara
     * explícitamente cuando no hay dato de contacto directo.
     */
    private String handleCustomerReference(List<String> focusIds, String message) {

        log.info("CHAT_INTENT_CUSTOMER_REFERENCE focusSize={}", focusIds.size());

        var candidates = opportunityMemory.findCandidatesByIds(focusIds);

        if (candidates.isEmpty()) {
            return "No tengo datos registrados de esos prospectos en Company Memory.";
        }

        var normalizedMessage = normalize(message);

        var mentioned = candidates.stream()
                .filter(c -> !c.name().isBlank() && normalizedMessage.contains(normalize(c.name())))
                .findFirst();

        if (mentioned.isPresent()) {
            return formatCustomerReferenceAnswer(mentioned.get(), false);
        }

        if (candidates.size() == 1) {
            return formatCustomerReferenceAnswer(candidates.get(0), false);
        }

        var topId = focusIds.get(0);

        var top = candidates.stream()
                .filter(c -> c.id().equals(topId))
                .findFirst()
                .orElse(candidates.get(0));

        return formatCustomerReferenceAnswer(top, true);
    }

    private String formatCustomerReferenceAnswer(LeadResponse candidate, boolean clarifyTopChoice) {

        var intro = clarifyTopChoice ? "Te muestro el de mayor probabilidad: " : "";

        var clarifyNote = clarifyTopChoice ? " Avisame si te referías a otro." : "";

        var contactNote = " No tengo un dato de contacto directo (teléfono/email) registrado para "
                + "este prospecto, solo la fuente donde se identificó.";

        return intro + candidate.name() + " (confidence="
                + String.format(Locale.ROOT, "%.2f", candidate.confidence())
                + ", fuente: " + candidate.source() + "): " + candidate.description()
                + "." + contactNote + clarifyNote;
    }
```

Luego, en `resolve(String message)`, ubicar este bloque (justo después de `handleMissionDetailsQuery`, antes de la resolución de `REFERENCE_PRONOUN`/`FOCUS_QUANTIFIER`):

```java
        var referenceMatcher = REFERENCE_PRONOUN.matcher(message);
        var focusQuantifierMatcher = FOCUS_QUANTIFIER.matcher(message);
```

y reemplazarlo por:

```java
        var customerFocusIds = resolveCustomerReference(message);

        if (customerFocusIds.isPresent()) {
            return handleCustomerReference(customerFocusIds.get(), message);
        }

        var referenceMatcher = REFERENCE_PRONOUN.matcher(message);
        var focusQuantifierMatcher = FOCUS_QUANTIFIER.matcher(message);
```

- [ ] **Step 5: Correr los tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: PASS — todos los tests de `ChatIntentRouterTest`, incluidos los 4 nuevos y todos los existentes (ninguno usa la palabra "contact").

- [ ] **Step 6: Correr toda la suite**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd app && git add src/main/java/com/aicompany/core/service/OpportunityMemoryService.java \
  src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
  src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: 'contactalo' resuelve contra el foco conversacional de prospectos"
```

---

## Verificación en vivo (después de completar las 5 tareas, fuera del ciclo TDD)

No es parte de ninguna tarea individual — es la verificación final de todo el sub-proyecto, mismo criterio que el resto de `docs/HISTORY.md`:

1. Rebuild y redeploy del contenedor `company-core` con esta rama.
2. Correr una misión real (o reusar una existente con candidatos LEAD ya generados, p. ej. `MISSION-1789701859658`).
3. Chat: `"¿Qué oportunidades concretas tenemos en la misión <id> y qué prospectos reales están asociados a cada una?"` — confirmar que trae los prospectos reales ordenados por confidence, no solo la instrucción de la misión.
4. Chat, follow-up: `"Contactalo"` — confirmar que resuelve al de mayor confidence (o al mencionado por nombre) y que la respuesta declara explícitamente que no hay dato de contacto real, sin inventar ninguno.
5. Confirmar con Cypher que los nodos `Customer{status:'LEAD'}` nuevos tienen la propiedad `confidence` poblada.

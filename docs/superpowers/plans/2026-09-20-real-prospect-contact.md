# Contacto real por email a prospectos — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que "contactalo" en el chat ejecute un envío real de email a un prospecto cuando el agente encontró un contacto público real, con un registro de auditoría real (`ContactAttempt`) y protección real contra doble envío (CAS sobre `Customer.status`).

**Architecture:** `AgentResult.CustomerCandidate` gana `contactEmail`/`contactEmailSource` (opcionales, acoplados). `OpportunityMemoryService` persiste esos campos y gana un ciclo de vida real de contacto (`claimForContact` CAS → `recordContactAttempt` → `markContactSent`/`markContactFailed`, con un nodo `ContactAttempt` nuevo). `AlertMailService.sendToExternal` manda a un destinatario arbitrario (no siempre al `alertEmail` del fundador como `send()`) y devuelve un resultado real (`ExternalMailResult`). `CompanyTools.contactProspect` conecta todo; `ChatIntentRouter` solo delega en él.

**Tech Stack:** Java 21, Spring Boot 4.1.1, `neo4j-java-driver`, `spring-boot-starter-mail`, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-20-real-prospect-contact-design.md`

## Global Constraints

- **Sin Governance, sin secret manager, sin `providerMessageId`, sin rate limiting, sin métricas, sin feed de actividad** — todo documentado como fuera de alcance en el spec, ninguna tarea de este plan los toca.
- El email real es siempre válido tanto de una persona puntual como uno general/de ventas de la empresa (`info@`/`ventas@`/`sales@`/`contacto@`) — nunca se distingue entre ambos en el código.
- `contactEmailSource` es obligatorio cuando `contactEmail` viene informado — nunca se acepta un email sin de dónde salió.
- El CAS sobre `Customer.status` (`LEAD` → `CONTACT_IN_PROGRESS`) es el único mecanismo real contra doble envío — no se agrega ninguna idempotency key adicional.
- Un envío fallido revierte `Customer.status` a `LEAD` (recuperable, se puede reintentar) — nunca queda atascado en `CONTACT_IN_PROGRESS`.
- El contenido del email es 100% determinista en Java — nunca pasa por el CEO.
- `mvn test` debe quedar en verde después de cada tarea.

---

### Task 1: `contactEmail`/`contactEmailSource` en el contrato del agente

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/AgentResult.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/model/AgentResultSchema.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/AgentRuntime.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/AgentResultValidator.java`
- Modify: `app/src/test/java/com/aicompany/core/agent/validation/AgentResultValidatorTest.java`

**Interfaces:**
- Produces: `AgentResult.CustomerCandidate(name, description, source, sourceType, confidence, contactEmail, contactEmailSource)` (7 campos, constructor de compatibilidad de 5 args existente) — usado por la Task 2.

- [ ] **Step 1: Agregar `contactEmail`/`contactEmailSource` al record `CustomerCandidate`**

En `app/src/main/java/com/aicompany/core/AgentResult.java`, reemplazar:

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

por:

```java
    public record CustomerCandidate(
            String name,
            String description,
            String source,
            String sourceType,
            double confidence,
            String contactEmail,
            String contactEmailSource
    ) {

        /**
         * Constructor de compatibilidad sin datos de contacto (ambos
         * quedan {@code null}) -- evita tocar los `new
         * AgentResult.CustomerCandidate(...)` de 5 args ya existentes en
         * {@code AgentResultValidatorTest}/{@code MissionExecutorTest}.
         */
        public CustomerCandidate(
                String name,
                String description,
                String source,
                String sourceType,
                double confidence) {

            this(name, description, source, sourceType, confidence, null, null);
        }
    }
```

- [ ] **Step 2: Correr la suite completa y confirmar que sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 254/254 (sin cambios de conteo todavía -- este step es solo el record, el constructor de compatibilidad preserva los 3 call-sites de test existentes).

- [ ] **Step 3: Agregar `contactEmail`/`contactEmailSource` al JSON Schema**

En `app/src/main/java/com/aicompany/core/agent/model/AgentResultSchema.java`, reemplazar:

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
                    "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1)
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

por:

```java
    private static final Map<String, Object> CUSTOMER_CANDIDATE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.ofEntries(
                    Map.entry("name", Map.of("type", "string", "minLength", 1)),
                    Map.entry("description", Map.of("type", "string", "minLength", 1)),
                    Map.entry("source", Map.of("type", "string")),
                    Map.entry("sourceType", Map.of(
                            "type", "string",
                            "enum", EVIDENCE_SOURCE_TYPES
                    )),
                    Map.entry("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1)),
                    Map.entry("contactEmail", Map.of("type", "string")),
                    Map.entry("contactEmailSource", Map.of("type", "string"))
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

(`contactEmail`/`contactEmailSource` deliberadamente **no** están en `required` -- un agente puede legítimamente no encontrar ningún canal público. `Map.ofEntries` en vez de `Map.of` porque `Map.of` no soporta más de 10 pares clave-valor.)

- [ ] **Step 4: Correr la suite completa y confirmar que sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 254/254.

- [ ] **Step 5: Agregar la instrucción de prompt para buscar el contacto**

En `app/src/main/java/com/aicompany/core/agent/AgentRuntime.java`, dentro del bloque `REGLAS:` del prompt (método `buildPrompt`), reemplazar:

```
                - customerCandidates es para EMPRESAS CONCRETAS Y
                  NOMBRADAS que hayas identificado en tu
                  investigación — no un segmento de mercado con
                  nombre inventado ("Studio PixelCraft" citando un
                  artículo genérico de tendencias del sector NO es
                  un candidato válido). La fuente (source) debe ser
                  la página, perfil o mención específica de ESA
                  empresa puntual, nunca un artículo general de la
                  industria. Nunca un cliente real ni contactado,
                  eso solo lo registra un humano. Si no
                  identificaste ninguna empresa real y específica,
                  dejá la lista vacía — no inventes una ni la
                  fuerces a partir de un segmento genérico.
                - confidence de cada candidato: alto (mayor a 0.7)
                  solo si es una empresa real, nombrada, con fuente
                  específica de ESA empresa; bajo (menor a 0.4) si
                  en realidad es más un segmento de mercado que una
                  empresa puntual verificable. Nunca reportes alto
                  solo porque el nombre suena a empresa real.
```

por:

```
                - customerCandidates es para EMPRESAS CONCRETAS Y
                  NOMBRADAS que hayas identificado en tu
                  investigación — no un segmento de mercado con
                  nombre inventado ("Studio PixelCraft" citando un
                  artículo genérico de tendencias del sector NO es
                  un candidato válido). La fuente (source) debe ser
                  la página, perfil o mención específica de ESA
                  empresa puntual, nunca un artículo general de la
                  industria. Nunca un cliente real ni contactado,
                  eso solo lo registra un humano. Si no
                  identificaste ninguna empresa real y específica,
                  dejá la lista vacía — no inventes una ni la
                  fuerces a partir de un segmento genérico.
                - confidence de cada candidato: alto (mayor a 0.7)
                  solo si es una empresa real, nombrada, con fuente
                  específica de ESA empresa; bajo (menor a 0.4) si
                  en realidad es más un segmento de mercado que una
                  empresa puntual verificable. Nunca reportes alto
                  solo porque el nombre suena a empresa real.
                - contactEmail (opcional) es para un canal de
                  contacto público REAL de esa empresa puntual —
                  tanto un email general/de ventas de la empresa
                  ("info@", "ventas@", "sales@", "contacto@", visto
                  en su sitio o pie de página) como el de una
                  persona puntual son igual de válidos, no hace
                  falta que sea de un individuo nombrado.
                  contactEmailSource es OBLIGATORIO si reportás
                  contactEmail: debe ser la página exacta donde lo
                  viste publicado. Nunca fabriques un email a partir
                  del dominio o el nombre de la empresa (ej.
                  "nombre@empresa.com" sin haberlo visto realmente
                  publicado en ningún lado). Si no encontraste
                  ningún canal público real, dejá ambos campos
                  vacíos.
```

- [ ] **Step 6: Correr la suite completa y confirmar que sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 254/254 (este step solo cambia texto de prompt, sin código ejecutable nuevo).

- [ ] **Step 7: Escribir el test de validación (falla primero)**

Agregar este test a `app/src/test/java/com/aicompany/core/agent/validation/AgentResultValidatorTest.java`, junto a los otros tests de `customerCandidate` (después de `acceptsCustomerCandidateWithConfidenceInRange`):

```java
    @Test
    void rejectsCustomerCandidateWithContactEmailButNoSource() {
        var candidate = new AgentResult.CustomerCandidate(
                "Panadería El Sol",
                "Identificada en estudio de mercado",
                "https://example.com",
                "WEB",
                0.6,
                "ventas@panaderiaelsol.com",
                null
        );

        var validation = validator.validate(resultWithCandidates(List.of(candidate)));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
                .anyMatch(error -> error.contains("contactEmail") && error.contains("contactEmailSource")));
    }

    @Test
    void acceptsCustomerCandidateWithContactEmailAndSource() {
        var candidate = new AgentResult.CustomerCandidate(
                "Panadería El Sol",
                "Identificada en estudio de mercado",
                "https://example.com",
                "WEB",
                0.6,
                "ventas@panaderiaelsol.com",
                "https://panaderiaelsol.com/contacto"
        );

        var validation = validator.validate(resultWithCandidates(List.of(candidate)));

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void acceptsCustomerCandidateWithoutAnyContactEmail() {
        var candidate = new AgentResult.CustomerCandidate(
                "Panadería El Sol",
                "Identificada en estudio de mercado",
                "https://example.com",
                "WEB",
                0.6
        );

        var validation = validator.validate(resultWithCandidates(List.of(candidate)));

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }
```

- [ ] **Step 8: Ejecutar los tests nuevos y confirmar que fallan**

Run: `cd app && mvn test -Dtest=AgentResultValidatorTest#rejectsCustomerCandidateWithContactEmailButNoSource`
Expected: FAIL (la regla de validación todavía no existe, así que este candidato pasa como válido en vez de ser rechazado).

- [ ] **Step 9: Agregar la regla de validación**

En `app/src/main/java/com/aicompany/core/agent/validation/AgentResultValidator.java`, dentro de `validateCustomerCandidates`, reemplazar:

```java
            if (candidate.confidence() < 0.0
                    || candidate.confidence() > 1.0) {

                errors.add(
                        "customerCandidate confidence debe estar entre 0 y 1: "
                                + candidate.name()
                );
            }
        }
    }
```

por:

```java
            if (candidate.confidence() < 0.0
                    || candidate.confidence() > 1.0) {

                errors.add(
                        "customerCandidate confidence debe estar entre 0 y 1: "
                                + candidate.name()
                );
            }

            if (candidate.contactEmail() != null
                    && !candidate.contactEmail().isBlank()
                    && (candidate.contactEmailSource() == null
                            || candidate.contactEmailSource().isBlank())) {

                errors.add(
                        "customerCandidate con contactEmail necesita contactEmailSource: "
                                + candidate.name()
                );
            }
        }
    }
```

- [ ] **Step 10: Ejecutar los 3 tests nuevos y confirmar que pasan**

Run: `cd app && mvn test -Dtest=AgentResultValidatorTest`
Expected: PASS, todos los tests de esta clase en verde (los 3 nuevos, más los ya existentes).

- [ ] **Step 11: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 257/257 (254 + 3 nuevos).

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/aicompany/core/AgentResult.java \
        app/src/main/java/com/aicompany/core/agent/model/AgentResultSchema.java \
        app/src/main/java/com/aicompany/core/agent/AgentRuntime.java \
        app/src/main/java/com/aicompany/core/agent/validation/AgentResultValidator.java \
        app/src/test/java/com/aicompany/core/agent/validation/AgentResultValidatorTest.java
git commit -m "CustomerCandidate gana contactEmail/contactEmailSource opcionales"
```

---

### Task 2: Persistencia de contacto + `ContactAttempt` en `OpportunityMemoryService`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/LeadResponse.java`
- Modify: `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java`

**Interfaces:**
- Consumes: `AgentResult.CustomerCandidate.contactEmail()`/`.contactEmailSource()` (Task 1).
- Produces: `LeadResponse.contactEmail()`/`.contactEmailSource()` (14 campos, constructor de compatibilidad de 12 args); `OpportunityMemoryService.claimForContact(String leadId) -> boolean`; `.recordContactAttempt(String leadId, String missionId, String opportunityId, String destination, String subject) -> String`; `.markContactSent(String attemptId, String leadId)`; `.markContactFailed(String attemptId, String leadId, String errorMessage)` — usados por la Task 4.

Sin test directo en este task (integración Neo4j pura, mismo criterio ya establecido para el resto de los `*MemoryService`) — se verifica en vivo al final del plan. La corrección se confirma porque la suite completa sigue compilando y en verde (ningún call-site existente de `LeadResponse` se rompe).

- [ ] **Step 1: Agregar `contactEmail`/`contactEmailSource` a `LeadResponse`**

Reemplazar el contenido completo de `app/src/main/java/com/aicompany/core/model/LeadResponse.java` por:

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
        double confidence,
        String contactEmail,
        String contactEmailSource
) {

    /**
     * Constructor de compatibilidad de 12 args (sin datos de contacto,
     * ambos quedan {@code null}) -- evita tocar los ~14 call-sites de
     * test que ya construyen {@code LeadResponse} con la firma
     * anterior.
     */
    public LeadResponse(
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
            double confidence) {

        this(
                id, name, description, source, sourceType,
                missionId, opportunityId, createdAt, status,
                discardReason, discardedAt, confidence,
                null, null
        );
    }
}
```

- [ ] **Step 2: Correr la suite completa y confirmar que sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 257/257 (el constructor de compatibilidad preserva todos los call-sites de test existentes).

- [ ] **Step 3: Persistir `contactEmail`/`contactEmailSource` en `recordCandidate`**

En `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java`, dentro de `recordCandidate`, reemplazar:

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

por:

```java
                tx.run("MATCH (o:Opportunity {id:$opportunityId}) " +
                                "MERGE (c:Customer {id:$candidateId}) " +
                                "ON CREATE SET c.missionId=$missionId, " +
                                "c.name=$name, c.status='LEAD', " +
                                "c.identifiedByAgent=$agentId, c.createdAt=$now, " +
                                "c.confidence=$confidence, c.contactEmail=$contactEmail, " +
                                "c.contactEmailSource=$contactEmailSource " +
                                "SET c.updatedAt=$now " +
                                "MERGE (o)-[:HAS_CANDIDATE]->(c)",
                        Map.of(
                                "opportunityId", missionId + "-OPPORTUNITY",
                                "candidateId", candidateId,
                                "missionId", missionId,
                                "name", candidate.name() == null ? "" : candidate.name(),
                                "agentId", agentId,
                                "confidence", candidate.confidence(),
                                "contactEmail", candidate.contactEmail() == null ? "" : candidate.contactEmail(),
                                "contactEmailSource", candidate.contactEmailSource() == null ? "" : candidate.contactEmailSource(),
                                "now", now
                        ));
```

- [ ] **Step 4: Agregar `contactEmail`/`contactEmailSource` a las 4 lecturas de `LeadResponse`**

En el mismo archivo, reemplazar `listLeads()` completo:

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
                                    "coalesce(c.confidence, 0.0) AS confidence, " +
                                    "c.contactEmail AS contactEmail, c.contactEmailSource AS contactEmailSource " +
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
                            r.get("confidence").asDouble(0.0),
                            r.get("contactEmail").asString(""),
                            r.get("contactEmailSource").asString("")
                    ));
        }
    }
```

Reemplazar `discardLead` completo:

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
                                "coalesce(c.confidence, 0.0) AS confidence, " +
                                "c.contactEmail AS contactEmail, c.contactEmailSource AS contactEmailSource",
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
                        r.get("confidence").asDouble(0.0),
                        r.get("contactEmail").asString(""),
                        r.get("contactEmailSource").asString("")
                ));
            });
        }
    }
```

Reemplazar `listCandidatesForMission` completo:

```java
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
                                    "ORDER BY coalesce(c.confidence, 0.0) DESC",
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

por:

```java
    public List<LeadResponse> listCandidatesForMission(String missionId) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (o:Opportunity {missionId:$missionId})-[:HAS_CANDIDATE]->(c:Customer {status:'LEAD'}) " +
                                    "OPTIONAL MATCH (c)-[:HAS_EVIDENCE]->(e:Evidence) " +
                                    "RETURN c.id AS id, c.name AS name, c.missionId AS missionId, " +
                                    "o.id AS opportunityId, c.createdAt AS createdAt, " +
                                    "e.description AS description, e.source AS source, " +
                                    "e.sourceType AS sourceType, c.status AS status, " +
                                    "coalesce(c.confidence, 0.0) AS confidence, " +
                                    "c.contactEmail AS contactEmail, c.contactEmailSource AS contactEmailSource " +
                                    "ORDER BY coalesce(c.confidence, 0.0) DESC",
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
                            r.get("confidence").asDouble(0.0),
                            r.get("contactEmail").asString(""),
                            r.get("contactEmailSource").asString("")
                    ));
        }
    }
```

Reemplazar `findCandidatesByIds` completo:

```java
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

por:

```java
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
                                    "coalesce(c.confidence, 0.0) AS confidence, " +
                                    "c.contactEmail AS contactEmail, c.contactEmailSource AS contactEmailSource",
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
                            r.get("confidence").asDouble(0.0),
                            r.get("contactEmail").asString(""),
                            r.get("contactEmailSource").asString("")
                    ));
        }
    }
```

- [ ] **Step 5: Correr la suite completa y confirmar que sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 257/257.

- [ ] **Step 6: Agregar `claimForContact`, `recordContactAttempt`, `markContactSent`, `markContactFailed`**

En `app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java`, agregar estos 4 métodos nuevos, justo antes de `listRecent` (el último método de la clase):

```java
    /**
     * Reclama el prospecto para un intento de contacto real -- compara-
     * y-actualiza en una sola sentencia Cypher (el {@code MATCH} con
     * {@code status:'LEAD'} hace de guarda), mismo patrón que
     * {@link #markConverted}. Es el mecanismo real contra el doble
     * envío: dos "contactalo" casi simultáneos no pueden ganar ambos
     * este CAS -- el segundo (o cualquiera contra un lead ya
     * convertido/descartado/contactado) recibe {@code false}.
     */
    public boolean claimForContact(String leadId) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {

                var records = tx.run(
                        "MATCH (c:Customer {id:$id, status:'LEAD'}) " +
                                "SET c.status='CONTACT_IN_PROGRESS', c.updatedAt=$now " +
                                "RETURN c.id AS id",
                        Map.of("id", leadId, "now", Instant.now().toString())
                ).list();

                return !records.isEmpty();
            });
        }
    }

    /**
     * Crea el registro real de un intento de contacto ({@code
     * ContactAttempt}) -- auditoría real de "se intentó, con estos
     * datos exactos", independiente de si el envío después tuvo éxito
     * o no. Devuelve el id generado, usado por {@link #markContactSent}/
     * {@link #markContactFailed} para actualizar el mismo registro.
     */
    public String recordContactAttempt(
            String leadId,
            String missionId,
            String opportunityId,
            String destination,
            String subject) {

        var attemptId = leadId + "-CONTACT-" + Instant.now().toEpochMilli();

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (c:Customer {id:$leadId}) " +
                                "CREATE (a:ContactAttempt {id:$attemptId, prospectId:$leadId, " +
                                "missionId:$missionId, opportunityId:$opportunityId, " +
                                "channel:'EMAIL', destination:$destination, subject:$subject, " +
                                "status:'PENDING', requestedBy:'human', initiatedAt:$now}) " +
                                "MERGE (c)-[:HAS_CONTACT_ATTEMPT]->(a)",
                        Map.of(
                                "leadId", leadId,
                                "attemptId", attemptId,
                                "missionId", missionId == null ? "" : missionId,
                                "opportunityId", opportunityId == null ? "" : opportunityId,
                                "destination", destination,
                                "subject", subject,
                                "now", Instant.now().toString()
                        ));
                return null;
            });
        }

        return attemptId;
    }

    /**
     * El envío salió: {@code ContactAttempt} pasa a {@code SENT} y el
     * prospecto a {@code CONTACTADO} (sale de {@link #listLeads}/
     * {@link #listCandidatesForMission}, que filtran estrictamente
     * {@code status='LEAD'}).
     */
    public void markContactSent(String attemptId, String leadId) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var now = Instant.now().toString();

                tx.run("MATCH (a:ContactAttempt {id:$attemptId}) " +
                                "SET a.status='SENT', a.sentAt=$now",
                        Map.of("attemptId", attemptId, "now", now));

                tx.run("MATCH (c:Customer {id:$leadId}) " +
                                "SET c.status='CONTACTADO', c.updatedAt=$now",
                        Map.of("leadId", leadId, "now", now));

                return null;
            });
        }
    }

    /**
     * El envío falló: {@code ContactAttempt} pasa a {@code FAILED} con
     * el motivo real, y el prospecto **vuelve** a {@code LEAD} -- un
     * envío que no salió tiene que poder reintentarse después, no
     * quedar atascado en {@code CONTACT_IN_PROGRESS} para siempre.
     */
    public void markContactFailed(String attemptId, String leadId, String errorMessage) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var now = Instant.now().toString();

                tx.run("MATCH (a:ContactAttempt {id:$attemptId}) " +
                                "SET a.status='FAILED', a.errorMessage=$errorMessage",
                        Map.of(
                                "attemptId", attemptId,
                                "errorMessage", errorMessage == null ? "" : errorMessage
                        ));

                tx.run("MATCH (c:Customer {id:$leadId}) " +
                                "SET c.status='LEAD', c.updatedAt=$now",
                        Map.of("leadId", leadId, "now", now));

                return null;
            });
        }
    }
```

- [ ] **Step 7: Correr la suite completa y confirmar que sigue en verde**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 257/257 (los 4 métodos nuevos son integración Neo4j pura, sin test directo en este task).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/LeadResponse.java \
        app/src/main/java/com/aicompany/core/service/OpportunityMemoryService.java
git commit -m "LeadResponse gana contacto; OpportunityMemoryService gana ContactAttempt real"
```

---

### Task 3: `AlertMailService.sendToExternal` + `ProspectOutreachEmailTemplate`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/AlertMailService.java`
- Create: `app/src/main/java/com/aicompany/core/service/ProspectOutreachEmailTemplate.java`
- Modify: `app/src/test/java/com/aicompany/core/service/AlertMailServiceTest.java`
- Create: `app/src/test/java/com/aicompany/core/service/ProspectOutreachEmailTemplateTest.java`

**Interfaces:**
- Consumes: `LeadResponse.name()`/`.description()`/`.source()` (Task 2, ya existentes); `AlertEmailTemplate.html(String, String, boolean)` (ya existente, package-private).
- Produces: `AlertMailService.ExternalMailResult(boolean accepted, String errorMessage)`; `AlertMailService.sendToExternal(String to, String subject, String body) -> ExternalMailResult`; `ProspectOutreachEmailTemplate.subject(LeadResponse)`/`.body(LeadResponse)` — usados por la Task 4.

- [ ] **Step 1: Escribir el test de `sendToExternal` (falla primero)**

Agregar estos 3 tests a `app/src/test/java/com/aicompany/core/service/AlertMailServiceTest.java`, al final de la clase (antes del cierre):

```java
    @Test
    void sendToExternalSendsToTheGivenRecipientNotTheAlertEmail() throws Exception {
        when(memory.alertEmail()).thenReturn("dapine@gmail.com");
        when(memory.systemEmail()).thenReturn("ai-company@gmail.com");
        when(memory.mailPassword()).thenReturn("app-password-secreta");
        when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());

        var result = alertMailService.sendToExternal(
                "ventas@panaderiaelsol.com", "Oportunidad de colaboración", "Cuerpo real."
        );

        assertTrue(result.accepted());

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());

        var message = captor.getValue();
        message.saveChanges();
        assertEquals("ventas@panaderiaelsol.com", message.getAllRecipients()[0].toString());
        assertEquals("ai-company@gmail.com", message.getFrom()[0].toString());
    }

    @Test
    void sendToExternalReturnsNotAcceptedWhenTheSystemAccountIsNotConfigured() {
        when(memory.systemEmail()).thenReturn("");
        when(memory.mailPassword()).thenReturn("");

        var result = alertMailService.sendToExternal("ventas@panaderiaelsol.com", "asunto", "cuerpo");

        assertFalse(result.accepted());
        assertNotNull(result.errorMessage());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendToExternalNeverThrowsWhenTheMailSenderFails() {
        when(memory.systemEmail()).thenReturn("ai-company@gmail.com");
        when(memory.mailPassword()).thenReturn("app-password-secreta");
        when(mailSender.createMimeMessage()).thenReturn(newMimeMessage());
        doThrow(new MailSendFailure()).when(mailSender).send(any(MimeMessage.class));

        var result = assertDoesNotThrow(() ->
                alertMailService.sendToExternal("ventas@panaderiaelsol.com", "asunto", "cuerpo"));

        assertFalse(result.accepted());
        assertNotNull(result.errorMessage());
    }
```

- [ ] **Step 2: Ejecutar los tests y confirmar que fallan**

Run: `cd app && mvn test -Dtest=AlertMailServiceTest#sendToExternalSendsToTheGivenRecipientNotTheAlertEmail`
Expected: FAIL — no se encuentra el símbolo `sendToExternal`.

- [ ] **Step 3: Agregar `ExternalMailResult` y `sendToExternal`**

En `app/src/main/java/com/aicompany/core/service/AlertMailService.java`, agregar este record y método nuevos, después del constructor y antes de `send`:

```java
    /**
     * Sin {@code providerMessageId}: {@code JavaMailSenderImpl.send(...)}
     * es {@code void} -- SMTP genérico (Gmail vía {@code spring.mail.*})
     * no da ese dato de forma confiable, a diferencia de un proveedor de
     * email tipo API (SendGrid/Mailgun). No se inventa un campo que no
     * existe.
     */
    public record ExternalMailResult(boolean accepted, String errorMessage) {
    }

    /**
     * Envío real a un destinatario arbitrario (nunca {@code alertEmail()}
     * del fundador, a diferencia de {@link #send}) -- usado para
     * contactar prospectos reales. A diferencia de {@code send}, que
     * nunca lanza porque una alerta interna fallida no debe tumbar
     * nada, este método SÍ devuelve el resultado real: el chat le tiene
     * que decir la verdad al fundador sobre si el correo a un
     * prospecto real salió o no.
     */
    public synchronized ExternalMailResult sendToExternal(String to, String subject, String body) {
        try {
            var systemEmail = memory.systemEmail();
            var password = memory.mailPassword();

            if (systemEmail == null || systemEmail.isBlank()
                    || password == null || password.isBlank()) {

                return new ExternalMailResult(
                        false,
                        "El correo propio del sistema no está configurado todavía "
                                + "(Settings del Command Center web)."
                );
            }

            mailSender.setUsername(systemEmail);
            mailSender.setPassword(password);

            var mimeMessage = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(systemEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, AlertEmailTemplate.html(subject, body, false));

            mailSender.send(mimeMessage);

            return new ExternalMailResult(true, null);

        } catch (Exception ex) {

            log.warn("No se pudo contactar al prospecto {}: {}", to, ex.getMessage());

            return new ExternalMailResult(
                    false,
                    ex.getMessage() == null ? "error desconocido" : ex.getMessage()
            );
        }
    }
```

- [ ] **Step 4: Ejecutar los 3 tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=AlertMailServiceTest`
Expected: PASS, todos los tests de esta clase en verde (los 3 nuevos, más los ya existentes de `send`).

- [ ] **Step 5: Escribir el test de la plantilla (falla primero)**

Crear `app/src/test/java/com/aicompany/core/service/ProspectOutreachEmailTemplateTest.java`:

```java
package com.aicompany.core.service;

import com.aicompany.core.model.LeadResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProspectOutreachEmailTemplateTest {

    private final LeadResponse candidate = new LeadResponse(
            "MISSION-1-CANDIDATE-SALES-0",
            "Panadería El Sol",
            "Panadería artesanal con presencia en 3 barrios de Bogotá",
            "https://panaderiaelsol.com",
            "WEB",
            "MISSION-1",
            "MISSION-1-OPPORTUNITY",
            Instant.now(),
            "LEAD",
            null,
            null,
            0.72
    );

    @Test
    void subjectAndBodyContainRealCandidateData() {
        var subject = ProspectOutreachEmailTemplate.subject(candidate);
        var body = ProspectOutreachEmailTemplate.body(candidate);

        assertTrue(subject.length() > 0);
        assertTrue(body.contains("Panadería El Sol"));
        assertTrue(body.contains("Panadería artesanal con presencia en 3 barrios de Bogotá"));
        assertTrue(body.contains("https://panaderiaelsol.com"));
    }

    @Test
    void greetingIsGenericNotAssumingAPersonName() {
        var body = ProspectOutreachEmailTemplate.body(candidate);

        assertTrue(body.contains("equipo de Panadería El Sol"));
        assertFalse(body.contains("Estimado"));
        assertFalse(body.contains("Sr."));
        assertFalse(body.contains("Sra."));
    }

    @Test
    void neverContainsUnresolvedPlaceholders() {
        var body = ProspectOutreachEmailTemplate.body(candidate);
        var subject = ProspectOutreachEmailTemplate.subject(candidate);

        assertFalse(body.contains("{"));
        assertFalse(body.contains("}"));
        assertFalse(subject.contains("{"));
        assertFalse(subject.contains("}"));
    }
}
```

- [ ] **Step 6: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=ProspectOutreachEmailTemplateTest`
Expected: FAIL — no se encuentra el símbolo `ProspectOutreachEmailTemplate`.

- [ ] **Step 7: Crear `ProspectOutreachEmailTemplate.java`**

```java
package com.aicompany.core.service;

import com.aicompany.core.model.LeadResponse;

/**
 * Contenido determinista del email de contacto real a un prospecto —
 * función pura, testeable sin JavaMail, mismo patrón que
 * {@link AlertEmailTemplate}. Nunca pasa por el CEO: solo usa datos ya
 * reales de {@link LeadResponse} (el mismo objeto que ya usa
 * {@code CompanyTools.formatCandidate}). Saludo deliberadamente
 * genérico -- funciona igual si el destinatario real es un buzón
 * general de ventas ("ventas@"/"info@") como si es el de una persona
 * puntual.
 */
final class ProspectOutreachEmailTemplate {

    private ProspectOutreachEmailTemplate() {
    }

    static String subject(LeadResponse candidate) {
        return "Oportunidad de colaboración con Forjai";
    }

    static String body(LeadResponse candidate) {

        return "Hola equipo de " + candidate.name() + ",\n\n"
                + "Somos Forjai, una empresa operada por inteligencia artificial. "
                + "Identificamos una posible oportunidad de colaboración con ustedes: "
                + candidate.description() + "\n\n"
                + "Los encontramos a través de: " + candidate.source() + "\n\n"
                + "Nos encantaría conversar brevemente si les interesa explorar esto juntos. "
                + "Pueden responder directamente a este correo.\n\n"
                + "Saludos,\n"
                + "Equipo Forjai";
    }
}
```

- [ ] **Step 8: Ejecutar el test y confirmar que pasa**

Run: `cd app && mvn test -Dtest=ProspectOutreachEmailTemplateTest`
Expected: PASS, 3/3.

- [ ] **Step 9: Ejecutar la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 263/263 (257 + 3 de `AlertMailServiceTest` + 3 de `ProspectOutreachEmailTemplateTest`).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/AlertMailService.java \
        app/src/main/java/com/aicompany/core/service/ProspectOutreachEmailTemplate.java \
        app/src/test/java/com/aicompany/core/service/AlertMailServiceTest.java \
        app/src/test/java/com/aicompany/core/service/ProspectOutreachEmailTemplateTest.java
git commit -m "AlertMailService.sendToExternal + ProspectOutreachEmailTemplate"
```

---

### Task 4: `CompanyTools.contactProspect` + cablear "contactalo" + documentar el evento

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyTools.java`
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Modify: `app/src/test/java/com/aicompany/core/service/CompanyToolsTest.java`
- Modify: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`
- Modify: `docs/EVENTS.md`

**Interfaces:**
- Consumes: `AlertMailService.sendToExternal`/`.ExternalMailResult` (Task 3), `ProspectOutreachEmailTemplate.subject`/`.body` (Task 3), `OpportunityMemoryService.claimForContact`/`.recordContactAttempt`/`.markContactSent`/`.markContactFailed` (Task 2), `LeadResponse.contactEmail()` (Task 2).
- Produces: `CompanyTools.contactProspect(LeadResponse candidate) -> String` — usado por `ChatIntentRouter`.

- [ ] **Step 1: Agregar `AlertMailService`/`CompanyEventPublisher` al constructor de `CompanyTools`**

En `app/src/main/java/com/aicompany/core/service/CompanyTools.java`, agregar el import:

```java
import com.aicompany.core.event.CompanyEventPublisher;

import java.util.Map;
```

(agregar `java.util.Map` junto a los imports `java.util.*` ya existentes, si no está).

Reemplazar el bloque de campos y constructor:

```java
    private final MissionService missionService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CustomerMemoryService customerMemory;
    private final ConversationMemoryService conversationMemory;
    private final ActivityMemoryService activityMemory;
    private final AppProperties appProperties;

    public CompanyTools(
            MissionService missionService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            ConversationMemoryService conversationMemory,
            ActivityMemoryService activityMemory,
            AppProperties appProperties) {

        this.missionService = missionService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.conversationMemory = conversationMemory;
        this.activityMemory = activityMemory;
        this.appProperties = appProperties;
    }
```

por:

```java
    private final MissionService missionService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CustomerMemoryService customerMemory;
    private final ConversationMemoryService conversationMemory;
    private final ActivityMemoryService activityMemory;
    private final AppProperties appProperties;
    private final AlertMailService alertMailService;
    private final CompanyEventPublisher events;

    public CompanyTools(
            MissionService missionService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            ConversationMemoryService conversationMemory,
            ActivityMemoryService activityMemory,
            AppProperties appProperties,
            AlertMailService alertMailService,
            CompanyEventPublisher events) {

        this.missionService = missionService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.conversationMemory = conversationMemory;
        this.activityMemory = activityMemory;
        this.appProperties = appProperties;
        this.alertMailService = alertMailService;
        this.events = events;
    }
```

- [ ] **Step 2: Actualizar los 2 call-sites de test que construyen `CompanyTools` directamente**

En `app/src/test/java/com/aicompany/core/service/CompanyToolsTest.java`, agregar los imports:

```java
import com.aicompany.core.event.CompanyEventPublisher;
```

y reemplazar la construcción de `tools`:

```java
    private final CompanyTools tools = new CompanyTools(
            missionService, missionMemory, opportunityMemory, customerMemory, conversationMemory, activityMemory, appProperties
    );
```

por (agregando también los 2 campos mock nuevos justo antes, junto a los demás campos mock ya existentes de esta clase):

```java
    private final AlertMailService alertMailService = mock(AlertMailService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);

    private final CompanyTools tools = new CompanyTools(
            missionService, missionMemory, opportunityMemory, customerMemory, conversationMemory, activityMemory, appProperties,
            alertMailService, events
    );
```

Repetir el mismo cambio (mismos 2 imports/campos mock nuevos, mismo reemplazo de la construcción de `companyTools`) en `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`.

- [ ] **Step 3: Correr la suite completa y confirmar que compila (aunque todavía no hay tests nuevos)**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 263/263.

- [ ] **Step 4: Escribir los tests de `contactProspect` (fallan primero)**

Agregar estos 4 tests a `app/src/test/java/com/aicompany/core/service/CompanyToolsTest.java`, al final de la clase (antes del cierre):

```java
    @Test
    void contactProspectReturnsDeterministicMessageWithoutAnyContactChannel() {
        var candidate = new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol", "desc", "src", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(), "LEAD", null, null, 0.5,
                null, null
        );

        var response = tools.contactProspect(candidate);

        assertEquals(
                "No tengo un dato de contacto directo (teléfono/email) registrado para "
                        + "este prospecto, solo la fuente donde se identificó.",
                response
        );
        verifyNoInteractions(alertMailService);
        verify(opportunityMemory, never()).claimForContact(any());
    }

    @Test
    void contactProspectReturnsDeterministicMessageWhenAlreadyClaimedOrContacted() {
        var candidate = new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol", "desc", "src", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(), "LEAD", null, null, 0.5,
                "ventas@panaderiaelsol.com", "https://panaderiaelsol.com/contacto"
        );
        when(opportunityMemory.claimForContact("MISSION-1-CANDIDATE-SALES-0")).thenReturn(false);

        var response = tools.contactProspect(candidate);

        assertEquals("Este prospecto ya tiene un contacto en progreso o ya fue contactado.", response);
        verifyNoInteractions(alertMailService);
    }

    @Test
    void contactProspectSendsRealEmailAndMarksSentOnSuccess() {
        var candidate = new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol", "desc",
                "https://panaderiaelsol.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(), "LEAD", null, null, 0.5,
                "ventas@panaderiaelsol.com", "https://panaderiaelsol.com/contacto"
        );
        when(opportunityMemory.claimForContact("MISSION-1-CANDIDATE-SALES-0")).thenReturn(true);
        when(opportunityMemory.recordContactAttempt(
                eq("MISSION-1-CANDIDATE-SALES-0"), eq("MISSION-1"), eq("MISSION-1-OPPORTUNITY"),
                eq("ventas@panaderiaelsol.com"), any()
        )).thenReturn("MISSION-1-CANDIDATE-SALES-0-CONTACT-123");
        when(alertMailService.sendToExternal(eq("ventas@panaderiaelsol.com"), any(), any()))
                .thenReturn(new AlertMailService.ExternalMailResult(true, null));

        var response = tools.contactProspect(candidate);

        assertTrue(response.contains("ventas@panaderiaelsol.com"));
        assertTrue(response.contains("MISSION-1-CANDIDATE-SALES-0-CONTACT-123"));
        verify(opportunityMemory).markContactSent("MISSION-1-CANDIDATE-SALES-0-CONTACT-123", "MISSION-1-CANDIDATE-SALES-0");
        verify(opportunityMemory, never()).markContactFailed(any(), any(), any());
        verify(events).publish(
                eq("EMPRESA_PROSPECT_CONTACTED"), eq("MISSION-1"), any(), eq("ceo"),
                eq(Map.of(
                        "leadId", "MISSION-1-CANDIDATE-SALES-0",
                        "attemptId", "MISSION-1-CANDIDATE-SALES-0-CONTACT-123",
                        "recipientEmail", "ventas@panaderiaelsol.com"
                ))
        );
    }

    @Test
    void contactProspectMarksFailedAndNeverMarksSentWhenSendingFails() {
        var candidate = new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol", "desc",
                "https://panaderiaelsol.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(), "LEAD", null, null, 0.5,
                "ventas@panaderiaelsol.com", "https://panaderiaelsol.com/contacto"
        );
        when(opportunityMemory.claimForContact("MISSION-1-CANDIDATE-SALES-0")).thenReturn(true);
        when(opportunityMemory.recordContactAttempt(any(), any(), any(), any(), any()))
                .thenReturn("MISSION-1-CANDIDATE-SALES-0-CONTACT-123");
        when(alertMailService.sendToExternal(any(), any(), any()))
                .thenReturn(new AlertMailService.ExternalMailResult(false, "smtp no configurado"));

        var response = tools.contactProspect(candidate);

        assertTrue(response.contains("smtp no configurado"));
        verify(opportunityMemory).markContactFailed(
                "MISSION-1-CANDIDATE-SALES-0-CONTACT-123", "MISSION-1-CANDIDATE-SALES-0", "smtp no configurado"
        );
        verify(opportunityMemory, never()).markContactSent(any(), any());
        verify(events).publish(
                eq("EMPRESA_PROSPECT_CONTACT_FAILED"), eq("MISSION-1"), any(), eq("ceo"),
                eq(Map.of(
                        "leadId", "MISSION-1-CANDIDATE-SALES-0",
                        "attemptId", "MISSION-1-CANDIDATE-SALES-0-CONTACT-123",
                        "reason", "smtp no configurado"
                ))
        );
    }
```

(agregar los imports `static org.mockito.ArgumentMatchers.eq` y `static org.mockito.Mockito.verifyNoInteractions` a `CompanyToolsTest.java` si no están ya presentes).

- [ ] **Step 5: Ejecutar los tests y confirmar que fallan**

Run: `cd app && mvn test -Dtest=CompanyToolsTest#contactProspectReturnsDeterministicMessageWithoutAnyContactChannel`
Expected: FAIL — no se encuentra el símbolo `contactProspect`.

- [ ] **Step 6: Implementar `contactProspect` en `CompanyTools`**

Agregar este método nuevo, al final de la clase (antes de `formatCandidate`):

```java
    /**
     * Dispara el contacto real (email) a un prospecto ya resuelto por
     * {@code ChatIntentRouter} contra el foco conversacional -- el
     * comando "contactalo" del fundador ejecuta este método de
     * inmediato, sin paso de confirmación intermedio (decisión
     * explícita del usuario, ver el spec).
     */
    public String contactProspect(LeadResponse candidate) {

        if (candidate.contactEmail() == null || candidate.contactEmail().isBlank()) {
            return "No tengo un dato de contacto directo (teléfono/email) registrado para "
                    + "este prospecto, solo la fuente donde se identificó.";
        }

        if (!opportunityMemory.claimForContact(candidate.id())) {
            return "Este prospecto ya tiene un contacto en progreso o ya fue contactado.";
        }

        var subject = ProspectOutreachEmailTemplate.subject(candidate);
        var body = ProspectOutreachEmailTemplate.body(candidate);

        var attemptId = opportunityMemory.recordContactAttempt(
                candidate.id(), candidate.missionId(), candidate.opportunityId(),
                candidate.contactEmail(), subject
        );

        var result = alertMailService.sendToExternal(candidate.contactEmail(), subject, body);

        if (!result.accepted()) {

            opportunityMemory.markContactFailed(attemptId, candidate.id(), result.errorMessage());

            events.publish(
                    "EMPRESA_PROSPECT_CONTACT_FAILED",
                    candidate.missionId(), null, "ceo",
                    Map.of("leadId", candidate.id(), "attemptId", attemptId, "reason", result.errorMessage())
            );

            return "Intenté enviar el correo, pero no se pudo (" + result.errorMessage() + "). "
                    + "El intento quedó registrado y el prospecto sigue disponible para reintentar.";
        }

        opportunityMemory.markContactSent(attemptId, candidate.id());

        events.publish(
                "EMPRESA_PROSPECT_CONTACTED",
                candidate.missionId(), null, "ceo",
                Map.of("leadId", candidate.id(), "attemptId", attemptId, "recipientEmail", candidate.contactEmail())
        );

        return "Listo, le mandé un correo real a " + candidate.contactEmail()
                + ". (ContactAttempt " + attemptId + ", estado: SENT)";
    }
```

- [ ] **Step 7: Ejecutar los 4 tests y confirmar que pasan**

Run: `cd app && mvn test -Dtest=CompanyToolsTest`
Expected: PASS, todos los tests de esta clase en verde.

- [ ] **Step 8: Cablear "contactalo" en `ChatIntentRouter`**

En `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`, reemplazar `formatCustomerReferenceAnswer` completo:

```java
    private String formatCustomerReferenceAnswer(LeadResponse candidate, boolean clarifyTopChoice) {

        var intro = clarifyTopChoice ? "Te muestro el de mayor probabilidad: " : "";

        var clarifyNote = clarifyTopChoice ? " Avisame si te referías a otro." : "";

        var contactNote = " No tengo un dato de contacto directo (teléfono/email) registrado para "
                + "este prospecto, solo la fuente donde se identificó.";

        return intro + companyTools.formatCandidate(candidate) + "." + contactNote + clarifyNote;
    }
```

por:

```java
    private String formatCustomerReferenceAnswer(LeadResponse candidate, boolean clarifyTopChoice) {

        var intro = clarifyTopChoice ? "Te muestro el de mayor probabilidad: " : "";

        var clarifyNote = clarifyTopChoice ? " Avisame si te referías a otro." : "";

        var contactResult = companyTools.contactProspect(candidate);

        return intro + companyTools.formatCandidate(candidate) + ". " + contactResult + clarifyNote;
    }
```

- [ ] **Step 9: Escribir el test de ruteo (falla primero)**

Agregar este test a `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`, cerca de los otros tests de `handleCustomerReference`/"contactalo" ya existentes:

```java
    @Test
    void contactCommandDelegatesToCompanyToolsContactProspect() {
        var candidate = new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol", "desc", "src", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(), "LEAD", null, null, 0.9,
                "ventas@panaderiaelsol.com", "https://panaderiaelsol.com/contacto"
        );
        when(conversationMemory.lastMentioned()).thenReturn(
                Optional.of(new LastMentioned("CUSTOMER", List.of("MISSION-1-CANDIDATE-SALES-0")))
        );
        when(opportunityMemory.findCandidatesByIds(List.of("MISSION-1-CANDIDATE-SALES-0")))
                .thenReturn(List.of(candidate));

        router.route("contactalo");

        verify(opportunityMemory).claimForContact("MISSION-1-CANDIDATE-SALES-0");
    }
```

(`LastMentioned`/`Optional`/`List` ya deberían estar importados en este archivo — si `opportunityMemory` no es un campo ya existente de esta clase de test, revisar el bloque de campos: ya se usa en otros tests de "contactalo"/foco `CUSTOMER" existentes en este mismo archivo).

- [ ] **Step 10: Ejecutar el test y confirmar que falla**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest#contactCommandDelegatesToCompanyToolsContactProspect`
Expected: FAIL — `verify(opportunityMemory).claimForContact(...)` no se llamó nunca (el código viejo solo devolvía el mensaje fijo de "no tengo contacto").

- [ ] **Step 11: Ejecutar el test y confirmar que pasa (ya con el Step 8 aplicado)**

Run: `cd app && mvn test -Dtest=ChatIntentRouterTest`
Expected: PASS, todos los tests de esta clase en verde.

- [ ] **Step 12: Documentar los 2 eventos nuevos**

Agregar estas 2 líneas al final de `docs/EVENTS.md` (después de la última línea, `EMPRESA_CEO_PROVIDER_FALLBACK`):

```
EMPRESA_PROSPECT_CONTACTED (`CompanyTools.contactProspect`, `agentId="ceo"` siempre): se publica cuando el envío real de un email de contacto a un prospecto (`Customer{status:'LEAD'}`) fue aceptado por el sistema de correo. `data: {leadId, attemptId, recipientEmail}` (`attemptId` es el id del nodo `ContactAttempt` real creado para este intento). El prospecto pasa de `LEAD` a `CONTACTADO` en la misma operación — ver "Contacto real por email a prospectos" en `CLAUDE.md`.

EMPRESA_PROSPECT_CONTACT_FAILED (`CompanyTools.contactProspect`, `agentId="ceo"` siempre): se publica cuando el envío real falló (cuenta del sistema sin configurar, credenciales inválidas, red caída). `data: {leadId, attemptId, reason}`. El prospecto vuelve a `LEAD` (no queda atascado en `CONTACT_IN_PROGRESS`) para poder reintentarse después.
```

- [ ] **Step 13: Correr la suite completa**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS, 268/268 (263 + 4 de `CompanyToolsTest` + 1 de `ChatIntentRouterTest`).

- [ ] **Step 14: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CompanyTools.java \
        app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
        app/src/test/java/com/aicompany/core/service/CompanyToolsTest.java \
        app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java \
        docs/EVENTS.md
git commit -m "CompanyTools.contactProspect + cablear contactalo con envio real"
```

---

## Verificación en vivo (después de completar las 4 tareas)

1. Sembrar en Neo4j un `Customer{status:'LEAD'}` real con `contactEmail`/`contactEmailSource` reales (una dirección propia controlada por el usuario, para no mandar un email real a un tercero durante la prueba).
2. Chat real: setear el foco `CUSTOMER` sobre ese id (vía una consulta de oportunidades de la misión correspondiente) y escribir "contactalo" — confirmar que el email llega de verdad, que el `Customer` pasa a `CONTACTADO`, y que existe un nodo `ContactAttempt{status:'SENT'}` real enlazado.
3. Repetir "contactalo" inmediatamente — confirmar el mensaje determinista de "ya tiene un contacto en progreso o ya fue contactado", **sin** un segundo email real enviado.
4. Forzar un fallo real (temporalmente, una contraseña de sistema inválida en Settings) contra un segundo `Customer{status:'LEAD'}` sembrado — confirmar que el `Customer` vuelve a `LEAD` (no queda en `CONTACT_IN_PROGRESS`), que existe un `ContactAttempt{status:'FAILED'}` con el motivo real, y que "contactalo" de nuevo (ya con la contraseña real restaurada) sí puede reintentarlo.
5. Confirmar en el log/Kafka los eventos `EMPRESA_PROSPECT_CONTACTED`/`EMPRESA_PROSPECT_CONTACT_FAILED` reales de los pasos 2 y 4.
6. Documentar los resultados reales en `CLAUDE.md`, siguiendo la convención ya establecida del proyecto.

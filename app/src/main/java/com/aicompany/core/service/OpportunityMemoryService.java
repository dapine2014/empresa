package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.model.LeadResponse;
import com.aicompany.core.model.OpportunitySummary;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistencia del flujo "Opportunity → Customer candidato" —
 * completamente automático y 100% de nivel 🟢 (`empresa.md` §5: "analizar
 * mercados", "buscar nuevas oportunidades"): no contacta a nadie real, no
 * crea clientes ni transacciones reales. Eso sigue siendo, a propósito,
 * solo del fundador humano vía {@code CustomerController} — este servicio
 * nunca escribe evidencia con {@code verified=true} ni permite que un
 * candidato se confunda con un cliente real.
 *
 * <p>Grafo: {@code (:Mission)-[:HAS_OPPORTUNITY]->(:Opportunity)}, y
 * {@code (:Opportunity)-[:HAS_CANDIDATE]->(:Customer {status:'LEAD'})}
 * — una relación deliberadamente distinta de
 * {@code (:Mission)-[:HAS_CUSTOMER]->(:Customer)} que usa el flujo humano
 * de {@code CustomerMemoryService}, para no tocar ese código ya probado:
 * un candidato de agente y un cliente real registrado por un humano
 * pueden compartir el label {@code Customer} (son la misma idea en
 * distintas etapas de madurez — LEAD/PROSPECT/CUSTOMER/PAYING_CUSTOMER,
 * `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §19) pero llegan por caminos
 * separados.
 */
@Service
public class OpportunityMemoryService {

    private final Driver driver;

    public OpportunityMemoryService(Driver driver) {
        this.driver = driver;
    }

    /**
     * Una Opportunity por misión, creada siempre que la misión llega a
     * consolidación (al menos un agente completó) — representa la
     * oportunidad de negocio que la misión exploró, no una validación:
     * {@code status} siempre arranca en {@code IDENTIFIED}, nunca
     * {@code VALIDATED} (eso lo decide el inversionista humano, no este
     * servicio).
     */
    public void recordOpportunity(String missionId, String description) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                tx.run("MATCH (m:Mission {id:$missionId}) " +
                                "MERGE (o:Opportunity {id:$opportunityId}) " +
                                "ON CREATE SET o.missionId=$missionId, " +
                                "o.description=$description, o.status='IDENTIFIED', " +
                                "o.createdAt=$now " +
                                "SET o.updatedAt=$now " +
                                "MERGE (m)-[:HAS_OPPORTUNITY]->(o)",
                        Map.of(
                                "missionId", missionId,
                                "opportunityId", missionId + "-OPPORTUNITY",
                                "description", description == null ? "" : description,
                                "now", Instant.now().toString()
                        ));

                return null;
            });
        }
    }

    /**
     * Registra un candidato de cliente (LEAD) que un agente identificó
     * durante su investigación — nunca {@code verified=true}: es una
     * hipótesis de a quién vender, no una relación real confirmada por
     * evidencia (eso sigue siendo exclusivo de
     * {@code CustomerMemoryService.registerCustomer}, canal humano).
     */
    public void recordCandidate(
            String missionId,
            String agentId,
            int index,
            AgentResult.CustomerCandidate candidate) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var candidateId =
                        missionId + "-CANDIDATE-" + agentId.toUpperCase() + "-" + index;

                var now = Instant.now().toString();

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

                tx.run("MATCH (c:Customer {id:$candidateId}) " +
                                "MERGE (e:Evidence {id:$evidenceId}) " +
                                "SET e.missionId=$missionId, e.agentId=$agentId, " +
                                "e.description=$description, e.source=$source, " +
                                "e.sourceType=$sourceType, e.verified=false, " +
                                "e.updatedAt=$now " +
                                "MERGE (c)-[:HAS_EVIDENCE]->(e)",
                        Map.of(
                                "candidateId", candidateId,
                                "evidenceId", candidateId + "-EVIDENCE",
                                "missionId", missionId,
                                "agentId", agentId,
                                "description", candidate.description() == null ? "" : candidate.description(),
                                "source", candidate.source() == null ? "" : candidate.source(),
                                "sourceType", candidate.sourceType() == null ? "NONE" : candidate.sourceType(),
                                "now", now
                        ));

                return null;
            });
        }
    }

    public void recordCandidates(
            String missionId,
            String agentId,
            List<AgentResult.CustomerCandidate> candidates) {

        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        for (var i = 0; i < candidates.size(); i++) {

            var candidate = candidates.get(i);

            if (candidate == null) {
                continue;
            }

            recordCandidate(missionId, agentId, i, candidate);
        }
    }

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

    /**
     * Compara-y-actualiza en una sola sentencia Cypher (el {@code MATCH}
     * con {@code status:'LEAD'} hace de guarda): si el lead no existe o ya
     * cambió de estado (ya `CONVERTIDO` o `DESCARTADO`), la consulta no
     * devuelve filas y este método retorna vacío en vez de lanzar — es el
     * llamador ({@code LeadController}) quien decide qué excepción
     * corresponde. Guarda por `status` dentro de una única sentencia
     * Cypher — no hay lock explícito entre transacciones concurrentes;
     * para un solo fundador operando desde el Command Center el riesgo
     * práctico es despreciable, pero no es una garantía dura de exclusión
     * mutua.
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

    /**
     * Oportunidades más recientes — usado por el intent de consulta
     * "qué oportunidades tenemos" del Chat Intent Router (no hay pantalla
     * dedicada de Opportunities en v1 del Command Center web, pero el dato
     * ya existe y consultarlo es barato).
     */
    /**
     * Total real de oportunidades registradas — usado por el status
     * agregado de la empresa ({@code QueryIntent.COMPANY_STATUS} en
     * {@code ChatIntentRouter}), no una lista capada como
     * {@link #listRecent}.
     */
    public long countOpportunities() {
        try (var session = driver.session()) {
            return session.run("MATCH (o:Opportunity) RETURN count(o) AS total")
                    .single()
                    .get("total")
                    .asLong();
        }
    }

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

    public List<OpportunitySummary> listRecent(int limit) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (o:Opportunity) RETURN o.id AS id, o.missionId AS missionId, " +
                                    "o.description AS description, o.status AS status, " +
                                    "o.createdAt AS createdAt " +
                                    "ORDER BY o.createdAt DESC LIMIT $limit",
                            Map.of("limit", limit))
                    .list(r -> new OpportunitySummary(
                            r.get("id").asString(),
                            r.get("missionId").asString(),
                            r.get("description").asString(),
                            r.get("status").asString(),
                            Instant.parse(r.get("createdAt").asString())
                    ));
        }
    }
}

package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.model.OpportunitySummary;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
     * Oportunidades más recientes — usado por el intent de consulta
     * "qué oportunidades tenemos" del Chat Intent Router (no hay pantalla
     * dedicada de Opportunities en v1 del Command Center web, pero el dato
     * ya existe y consultarlo es barato).
     */
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

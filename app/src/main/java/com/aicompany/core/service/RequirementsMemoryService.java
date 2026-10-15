package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Spec 2026-10-03 Aria §1: versiones del spec de Aria por misión ((:Mission)-[:HAS_REQUIREMENTS]->). */
@Service
public class RequirementsMemoryService {

    public record Version(int version, String specJson, String status, String instruction, Instant createdAt) {
    }

    private final Driver driver;

    public RequirementsMemoryService(Driver driver) {
        this.driver = driver;
    }

    public int saveDraft(String missionId, String specJson, String instruction) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var next = tx.run("MATCH (m:Mission {id:$id}) OPTIONAL MATCH (m)-[:HAS_REQUIREMENTS]->(r) "
                        + "RETURN coalesce(max(r.version), 0) + 1 AS v", Map.of("id", missionId)).single().get("v").asInt();
                tx.run("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {status:'DRAFT'}) "
                        + "SET r.status = 'SUPERSEDED'", Map.of("id", missionId));
                tx.run("MATCH (m:Mission {id:$id}) CREATE (m)-[:HAS_REQUIREMENTS]->(:RequirementsVersion {version:$v, "
                                + "specJson:$spec, status:'DRAFT', instruction:$instruction, createdAt:datetime()})",
                        Map.of("id", missionId, "v", next, "spec", specJson,
                                "instruction", instruction == null ? "" : instruction));
                return next;
            });
        }
    }

    public Optional<Version> latest(String missionId) {
        return query(missionId, "");
    }

    public Optional<Version> approved(String missionId) {
        return query(missionId, " AND r.status = 'APPROVED'");
    }

    private Optional<Version> query(String missionId, String filter) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion) WHERE true" + filter
                            + " RETURN r.version AS v, r.specJson AS s, r.status AS st, r.instruction AS i, "
                            + "r.createdAt AS c ORDER BY r.version DESC LIMIT 1", Map.of("id", missionId))
                    .list(r -> new Version(r.get("v").asInt(), r.get("s").asString(), r.get("st").asString(),
                            r.get("i").isNull() ? "" : r.get("i").asString(),
                            r.get("c").isNull() ? null : r.get("c").asZonedDateTime().toInstant()))
                    .stream().findFirst();
        }
    }

    /**
     * Revisión final Aria (12): CAS contra dos decisiones simultáneas sobre la misma versión (doble clic): solo la primera
     * la reclama; la versión sigue en DRAFT hasta que la decisión la cambie.
     */
    public boolean claimDecision(String missionId, int version, String pendingAdjustment) {
        // Map.of no admite null: el ajuste es null cuando la decisión no es un ADJUST.
        var params = new java.util.HashMap<String, Object>();
        params.put("id", missionId);
        params.put("v", version);
        params.put("adjust", pendingAdjustment);
        try (var session = driver.session()) {
            return session.executeWrite(tx -> !tx.run("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion "
                    + "{version:$v}) WHERE r.status = 'DRAFT' AND r.decisionClaimedAt IS NULL SET r.decisionClaimedAt = datetime(), "
                    + "r.pendingAdjustment = $adjust RETURN r.version",
                    params).list().isEmpty());
        }
    }

    /** Spec misiones durables §2: el ajuste reclamado sobre la última versión DRAFT (Aria no llegó a escribir la nueva). */
    public Optional<String> pendingAdjustment(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {status:'DRAFT'}) "
                            + "WHERE r.decisionClaimedAt IS NOT NULL AND r.pendingAdjustment IS NOT NULL "
                            + "RETURN r.pendingAdjustment AS a ORDER BY r.version DESC LIMIT 1", Map.of("id", missionId))
                    .list(r -> r.get("a").asString()).stream().findFirst();
        }
    }

    public void approve(String missionId, int version, String approvedBy) {
        write("MATCH (m:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {version:$v}) "
                + "SET r.status='APPROVED', r.approvedBy=$by, r.approvedAt=datetime(), m.approvedSpecVersion=$v",
                Map.of("id", missionId, "v", version, "by", approvedBy));
    }

    /**
     * Spec 2026-10-14 Kael §1.2 (revisión final M-1): las HU y el diseño de Kael se aprueban juntos o ninguno, en una
     * sola transacción (un corte a mitad no deja HU aprobadas con el diseño en DRAFT).
     */
    public void approveWithDesign(String missionId, int specVersion, int designVersion, String approvedBy) {
        var params = Map.<String, Object>of("id", missionId, "v", specVersion, "d", designVersion, "by", approvedBy);
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (m:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {version:$v}) "
                        + "SET r.status='APPROVED', r.approvedBy=$by, r.approvedAt=datetime(), m.approvedSpecVersion=$v",
                        params);
                tx.run("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {version:$d}) "
                        + "SET d.status='APPROVED', d.approvedBy=$by, d.approvedAt=datetime() REMOVE d.pendingAdjustment",
                        params);
                return null;
            });
        }
    }

    /**
     * Revisión final M-1: el CAS de claimDecision (sin ajuste en las HU) y el ajuste pendiente en la versión del diseño,
     * en una sola transacción; solo la primera decisión gana.
     */
    public boolean claimDecisionForKael(String missionId, int specVersion, int designVersion, String adjustment) {
        var params = Map.<String, Object>of("id", missionId, "v", specVersion, "d", designVersion, "a", adjustment);
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var claimed = !tx.run("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {version:$v}) "
                        + "WHERE r.status = 'DRAFT' AND r.decisionClaimedAt IS NULL SET r.decisionClaimedAt = datetime(), "
                        + "r.pendingAdjustment = null RETURN r.version", params).list().isEmpty();
                if (claimed) {
                    tx.run("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {version:$d}) "
                            + "SET d.pendingAdjustment=$a", params);
                }
                return claimed;
            });
        }
    }

    public void reject(String missionId, int version) {
        write("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {version:$v}) SET r.status='REJECTED'",
                Map.of("id", missionId, "v", version));
    }

    /**
     * Revisión final Aria (16): misión del Development Group anterior a Aria (sin ninguna versión de requisitos y con un
     * plan de Neo ya hecho). Sus rondas reutilizan ese plan como antes y no necesitan a Aria.
     */
    public boolean predatesRequirements(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (m:Mission {id:$id}) RETURN NOT EXISTS { (m)-[:HAS_REQUIREMENTS]->() } "
                            + "AND EXISTS { (m)-[:HAS_TASK]->(:AgentTask {kind:'PLANNING', status:'COMPLETED'}) } AS legacy",
                            Map.of("id", missionId))
                    .list(r -> r.get("legacy").asBoolean()).stream().findFirst().orElse(false);
        }
    }

    public int builtIncrement(String missionId) {
        return intProperty(missionId, "builtIncrement");
    }

    public void setBuiltIncrement(String missionId, int increment) {
        write("MATCH (m:Mission {id:$id}) SET m.builtIncrement=$n", Map.of("id", missionId, "n", increment));
    }

    /** Revisión final (3): el incremento en construcción; cuenta como construido solo al llegar a AWAITING_INVESTOR. */
    public void setBuildingIncrement(String missionId, int increment) {
        write("MATCH (m:Mission {id:$id}) SET m.buildingIncrement=$n", Map.of("id", missionId, "n", increment));
    }

    public void confirmBuildingIncrement(String missionId) {
        write("MATCH (m:Mission {id:$id}) WHERE m.buildingIncrement IS NOT NULL "
                + "SET m.builtIncrement = m.buildingIncrement REMOVE m.buildingIncrement", Map.of("id", missionId));
    }

    /** El incremento en construcción o, si no hay, el último construido (0 = ninguno). */
    public int currentIncrement(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (m:Mission {id:$id}) RETURN coalesce(m.buildingIncrement, m.builtIncrement, 0) AS n",
                    Map.of("id", missionId)).list(r -> r.get("n").asInt()).stream().findFirst().orElse(0);
        }
    }

    public int incrementRounds(String missionId) {
        return intProperty(missionId, "incrementRounds");
    }

    /** Verificado en vivo (2026-10-04): se guarda qué ronda fue de qué incremento (el chat las etiquetaba mal). */
    public void addIncrementRound(String missionId, int round, int increment) {
        write("MATCH (m:Mission {id:$id}) SET m.incrementRounds = coalesce(m.incrementRounds, 0) + 1, "
                        + "m.incrementRoundNumbers = coalesce(m.incrementRoundNumbers, []) + $round, "
                        + "m.incrementNumbers = coalesce(m.incrementNumbers, []) + $increment",
                Map.of("id", missionId, "round", round, "increment", increment));
    }

    /** Ronda → incremento que se construyó en ella. */
    public Map<Integer, Integer> incrementRoundsByNumber(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (m:Mission {id:$id}) RETURN coalesce(m.incrementRoundNumbers, []) AS r, "
                            + "coalesce(m.incrementNumbers, []) AS n", Map.of("id", missionId))
                    .list(rec -> {
                        var rounds = rec.get("r").asList(v -> v.asInt());
                        var increments = rec.get("n").asList(v -> v.asInt());
                        var out = new java.util.LinkedHashMap<Integer, Integer>();
                        for (int i = 0; i < Math.min(rounds.size(), increments.size()); i++) {
                            out.put(rounds.get(i), increments.get(i));
                        }
                        return (Map<Integer, Integer>) out;
                    }).stream().findFirst().orElse(Map.of());
        }
    }

    private int intProperty(String missionId, String property) {
        try (var session = driver.session()) {
            return session.run("MATCH (m:Mission {id:$id}) RETURN coalesce(m." + property + ", 0) AS n",
                    Map.of("id", missionId)).list(r -> r.get("n").asInt()).stream().findFirst().orElse(0);
        }
    }

    /** Spec 2026-10-14 Kael (Review Focus 2): tras un ajuste a Kael, la versión de HU vuelve a poder decidirse. */
    public void releaseDecision(String missionId, int version) {
        write("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {version:$v}) "
                + "REMOVE r.decisionClaimedAt, r.pendingAdjustment", Map.of("id", missionId, "v", version));
    }

    private void write(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        }
    }
}

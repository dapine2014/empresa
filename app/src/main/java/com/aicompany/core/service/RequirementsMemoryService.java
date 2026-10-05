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

    public void approve(String missionId, int version, String approvedBy) {
        write("MATCH (m:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {version:$v}) "
                + "SET r.status='APPROVED', r.approvedBy=$by, r.approvedAt=datetime(), m.approvedSpecVersion=$v",
                Map.of("id", missionId, "v", version, "by", approvedBy));
    }

    public void reject(String missionId, int version) {
        write("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {version:$v}) SET r.status='REJECTED'",
                Map.of("id", missionId, "v", version));
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

    private void write(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        }
    }
}

package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Spec 2026-10-14 Kael §1.3: versiones del diseño de Kael ((:Mission)-[:HAS_INTERACTION_DESIGN]->). */
@Service
public class InteractionDesignMemoryService {

    public record Version(int version, String designJson, String status, int specVersion, String instruction) {
    }

    private final Driver driver;

    public InteractionDesignMemoryService(Driver driver) {
        this.driver = driver;
    }

    public int saveDraft(String missionId, String designJson, int specVersion, String instruction) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var next = tx.run("MATCH (m:Mission {id:$id}) OPTIONAL MATCH (m)-[:HAS_INTERACTION_DESIGN]->(d) "
                        + "RETURN coalesce(max(d.version), 0) + 1 AS v", Map.of("id", missionId)).single().get("v").asInt();
                tx.run("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {status:'DRAFT'}) "
                        + "SET d.status = 'SUPERSEDED'", Map.of("id", missionId));
                tx.run("MATCH (m:Mission {id:$id}) CREATE (m)-[:HAS_INTERACTION_DESIGN]->(:InteractionDesignVersion "
                                + "{version:$v, designJson:$json, status:'DRAFT', specVersion:$spec, instruction:$i, "
                                + "createdAt:datetime()})",
                        Map.of("id", missionId, "v", next, "json", designJson, "spec", specVersion,
                                "i", instruction == null ? "" : instruction));
                return next;
            });
        }
    }

    public Optional<Version> latest(String missionId) {
        return query(missionId, "");
    }

    public Optional<Version> approved(String missionId) {
        return query(missionId, " AND d.status = 'APPROVED'");
    }

    private Optional<Version> query(String missionId, String filter) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion) "
                            + "WHERE true" + filter + " RETURN d.version AS v, d.designJson AS j, d.status AS s, "
                            + "d.specVersion AS sv, d.instruction AS i ORDER BY d.version DESC LIMIT 1",
                    Map.of("id", missionId)).list(r -> new Version(r.get("v").asInt(), r.get("j").asString(),
                    r.get("s").asString(), r.get("sv").asInt(), r.get("i").isNull() ? "" : r.get("i").asString()))
                    .stream().findFirst();
        }
    }

    public void approve(String missionId, int version, String by) {
        write("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {version:$v}) "
                + "SET d.status='APPROVED', d.approvedBy=$by, d.approvedAt=datetime() REMOVE d.pendingAdjustment",
                Map.of("id", missionId, "v", version, "by", by));
    }

    public void reject(String missionId, int version) {
        write("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {version:$v}) "
                + "SET d.status='REJECTED'", Map.of("id", missionId, "v", version));
    }

    /** Spec §1.2: un ajuste a Aria deja colgando el diseño anterior. */
    public void supersedeDraft(String missionId) {
        write("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {status:'DRAFT'}) "
                + "SET d.status='SUPERSEDED'", Map.of("id", missionId));
    }

    public void setPendingAdjustment(String missionId, int version, String adjustment) {
        var params = new HashMap<String, Object>();
        params.put("id", missionId);
        params.put("v", version);
        params.put("a", adjustment);
        write("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {version:$v}) "
                + "SET d.pendingAdjustment=$a", params);
    }

    public Optional<String> pendingAdjustment(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion "
                            + "{status:'DRAFT'}) WHERE d.pendingAdjustment IS NOT NULL RETURN d.pendingAdjustment AS a "
                            + "ORDER BY d.version DESC LIMIT 1", Map.of("id", missionId))
                    .list(r -> r.get("a").asString()).stream().findFirst();
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

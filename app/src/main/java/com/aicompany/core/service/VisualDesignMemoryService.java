package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/** Spec 2026-11-01 front con diseño visual: versiones del sistema visual y las maquetas ((:Mission)-[:HAS_VISUAL_DESIGN]->). */
@Service
public class VisualDesignMemoryService {

    public record Version(int version, String designJson, String status, int specVersion, int interactionDesignVersion,
                          String instruction) {
    }

    private final Driver driver;

    public VisualDesignMemoryService(Driver driver) {
        this.driver = driver;
    }

    public int saveDraft(String missionId, String designJson, int specVersion, int interactionDesignVersion,
                         String instruction) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var next = tx.run("MATCH (m:Mission {id:$id}) OPTIONAL MATCH (m)-[:HAS_VISUAL_DESIGN]->(d) "
                        + "RETURN coalesce(max(d.version), 0) + 1 AS v", Map.of("id", missionId)).single().get("v").asInt();
                tx.run("MATCH (:Mission {id:$id})-[:HAS_VISUAL_DESIGN]->(d:VisualDesignVersion {status:'DRAFT'}) "
                        + "SET d.status = 'SUPERSEDED'", Map.of("id", missionId));
                tx.run("MATCH (m:Mission {id:$id}) CREATE (m)-[:HAS_VISUAL_DESIGN]->(:VisualDesignVersion "
                                + "{version:$v, designJson:$json, status:'DRAFT', specVersion:$spec, "
                                + "interactionDesignVersion:$ids, instruction:$i, createdAt:datetime()})",
                        Map.of("id", missionId, "v", next, "json", designJson, "spec", specVersion,
                                "ids", interactionDesignVersion, "i", instruction == null ? "" : instruction));
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
            return session.run("MATCH (:Mission {id:$id})-[:HAS_VISUAL_DESIGN]->(d:VisualDesignVersion) "
                            + "WHERE true" + filter + " RETURN d.version AS v, d.designJson AS j, d.status AS s, "
                            + "d.specVersion AS sv, d.interactionDesignVersion AS iv, d.instruction AS i "
                            + "ORDER BY d.version DESC LIMIT 1", Map.of("id", missionId))
                    .list(r -> new Version(r.get("v").asInt(), r.get("j").asString(), r.get("s").asString(),
                            r.get("sv").isNull() ? 0 : r.get("sv").asInt(), r.get("iv").isNull() ? 0 : r.get("iv").asInt(),
                            r.get("i").isNull() ? "" : r.get("i").asString()))
                    .stream().findFirst();
        }
    }

    /** Aprueba una versión: la que estaba APPROVED pasa a SUPERSEDED. */
    public void approve(String missionId, int version, String approvedBy) {
        write("MATCH (:Mission {id:$id})-[:HAS_VISUAL_DESIGN]->(o:VisualDesignVersion {status:'APPROVED'}) "
                + "WHERE o.version <> $v SET o.status='SUPERSEDED'", Map.of("id", missionId, "v", version));
        write("MATCH (:Mission {id:$id})-[:HAS_VISUAL_DESIGN]->(d:VisualDesignVersion {version:$v}) "
                + "SET d.status='APPROVED', d.approvedBy=$by, d.approvedAt=datetime()",
                Map.of("id", missionId, "v", version, "by", approvedBy == null ? "" : approvedBy));
    }

    /** Un ajuste al diseño de Kael o a las HU deja colgando el borrador visual anterior. */
    public void supersedeDraft(String missionId) {
        write("MATCH (:Mission {id:$id})-[:HAS_VISUAL_DESIGN]->(d:VisualDesignVersion {status:'DRAFT'}) "
                + "SET d.status='SUPERSEDED'", Map.of("id", missionId));
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

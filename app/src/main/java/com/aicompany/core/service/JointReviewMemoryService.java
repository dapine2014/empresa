package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Revisión conjunta (spec 2026-10-15): {@code (:Mission)-[:HAS_JOINT_REVIEW]->(:JointReview {number, round,
 * specVersion, designVersion, status, contradictionsJson, decisionsJson, detail, at})}. Cerrada = cualquier estado
 * (se guarda solo al terminar).
 */
@Service
public class JointReviewMemoryService {

    public record Review(int number, int round, int specVersion, int designVersion, String status,
                         String contradictionsJson, String decisionsJson, String detail, String at) {
    }

    private final Driver driver;

    public JointReviewMemoryService(Driver driver) {
        this.driver = driver;
    }

    public int save(String missionId, int round, int specVersion, int designVersion, String status,
                    String contradictionsJson, String decisionsJson, String detail) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var next = tx.run("MATCH (m:Mission {id:$id}) OPTIONAL MATCH (m)-[:HAS_JOINT_REVIEW]->(r) "
                        + "RETURN coalesce(max(r.number), 0) + 1 AS n", Map.of("id", missionId)).single().get("n").asInt();
                tx.run("MATCH (m:Mission {id:$id}) CREATE (m)-[:HAS_JOINT_REVIEW]->(:JointReview {number:$n, round:$round, "
                                + "specVersion:$spec, designVersion:$design, status:$status, contradictionsJson:$c, "
                                + "decisionsJson:$d, detail:$detail, at:$at})",
                        Map.of("id", missionId, "n", next, "round", round, "spec", specVersion, "design", designVersion,
                                "status", status, "c", contradictionsJson == null ? "[]" : contradictionsJson,
                                "d", decisionsJson == null ? "[]" : decisionsJson, "detail", detail == null ? "" : detail,
                                "at", Instant.now().toString()));
                return next;
            });
        }
    }

    public Optional<Review> closedFor(String missionId, int specVersion, int designVersion) {
        return query(missionId, " AND r.specVersion = $spec AND r.designVersion = $design",
                Map.of("id", missionId, "spec", specVersion, "design", designVersion)).stream().reduce((a, b) -> b);
    }

    public List<Review> all(String missionId) {
        return query(missionId, "", Map.of("id", missionId));
    }

    private List<Review> query(String missionId, String filter, Map<String, Object> params) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_JOINT_REVIEW]->(r:JointReview) WHERE true" + filter
                            + " RETURN r ORDER BY r.number", params)
                    .list(rec -> {
                        var r = rec.get("r");
                        return new Review(r.get("number").asInt(), r.get("round").asInt(), r.get("specVersion").asInt(),
                                r.get("designVersion").asInt(), r.get("status").asString(),
                                r.get("contradictionsJson").asString("[]"), r.get("decisionsJson").asString("[]"),
                                r.get("detail").asString(""), r.get("at").asString(""));
                    });
        }
    }
}

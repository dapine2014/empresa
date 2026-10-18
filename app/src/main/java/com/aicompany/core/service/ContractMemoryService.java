package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Contrato (spec 2026-10-16 contrato §1): versiones del contrato por misión ((:Mission)-[:HAS_CONTRACT]->). */
@Service
public class ContractMemoryService {

    public record Version(int version, String contractJson, String status, String instruction, Instant createdAt) {
    }

    private final Driver driver;

    public ContractMemoryService(Driver driver) {
        this.driver = driver;
    }

    public int saveDraft(String missionId, String contractJson, String instruction) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var next = tx.run("MATCH (m:Mission {id:$id}) OPTIONAL MATCH (m)-[:HAS_CONTRACT]->(c) "
                        + "RETURN coalesce(max(c.version), 0) + 1 AS v", Map.of("id", missionId)).single().get("v").asInt();
                tx.run("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion {status:'DRAFT'}) "
                        + "SET c.status = 'SUPERSEDED'", Map.of("id", missionId));
                tx.run("MATCH (m:Mission {id:$id}) CREATE (m)-[:HAS_CONTRACT]->(:ContractVersion {version:$v, "
                                + "contractJson:$json, status:'DRAFT', instruction:$instruction, createdAt:datetime()})",
                        Map.of("id", missionId, "v", next, "json", contractJson,
                                "instruction", instruction == null ? "" : instruction));
                return next;
            });
        }
    }

    public Optional<Version> latest(String missionId) {
        return query(missionId, "");
    }

    public Optional<Version> approved(String missionId) {
        return query(missionId, " AND c.status = 'APPROVED'");
    }

    private Optional<Version> query(String missionId, String filter) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion) WHERE true" + filter
                            + " RETURN c.version AS v, c.contractJson AS j, c.status AS st, c.instruction AS i, "
                            + "c.createdAt AS t ORDER BY c.version DESC LIMIT 1", Map.of("id", missionId))
                    .list(r -> new Version(r.get("v").asInt(), r.get("j").asString(), r.get("st").asString(),
                            r.get("i").isNull() ? "" : r.get("i").asString(),
                            r.get("t").isNull() ? null : r.get("t").asZonedDateTime().toInstant()))
                    .stream().findFirst();
        }
    }

    /** Review Focus 1: CAS contra dos decisiones simultáneas sobre la misma versión (doble clic). */
    public boolean claimDecision(String missionId, int version, String pendingAdjustment) {
        var params = new java.util.HashMap<String, Object>();
        params.put("id", missionId);
        params.put("v", version);
        params.put("adjust", pendingAdjustment);
        try (var session = driver.session()) {
            return session.executeWrite(tx -> !tx.run("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion "
                    + "{version:$v}) WHERE c.status = 'DRAFT' AND c.decisionClaimedAt IS NULL SET c.decisionClaimedAt = "
                    + "datetime(), c.pendingAdjustment = $adjust RETURN c.version", params).list().isEmpty());
        }
    }

    /** El ajuste reclamado sobre la última versión DRAFT (Aria no llegó a escribir la nueva). */
    public Optional<String> pendingAdjustment(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion {status:'DRAFT'}) "
                            + "WHERE c.decisionClaimedAt IS NOT NULL AND c.pendingAdjustment IS NOT NULL "
                            + "RETURN c.pendingAdjustment AS a ORDER BY c.version DESC LIMIT 1", Map.of("id", missionId))
                    .list(r -> r.get("a").asString()).stream().findFirst();
        }
    }

    public void approve(String missionId, int version, String approvedBy) {
        write("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion {version:$v}) "
                + "SET c.status='APPROVED', c.approvedBy=$by, c.approvedAt=datetime()",
                Map.of("id", missionId, "v", version, "by", approvedBy));
    }

    public void reject(String missionId, int version) {
        write("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion {version:$v}) SET c.status='REJECTED'",
                Map.of("id", missionId, "v", version));
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

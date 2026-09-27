package com.aicompany.core.service;

import com.aicompany.core.model.DependencyRef;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catálogo de dependencias gobernadas (spec §3): (:Dependency {id, ecosystem, name, version, status: APPROVED |
 * PENDING_APPROVAL | REJECTED, approvedBy, reasons, license, requestedByAgent, missionId, jobId, createdAt, decidedAt}).
 */
@Service
public class DependencyMemoryService {

    private final Driver driver;

    public DependencyMemoryService(Driver driver) {
        this.driver = driver;
    }

    public Optional<String> status(DependencyRef ref) {
        try (var session = driver.session()) {
            var records = session.run("MATCH (d:Dependency {id:$id}) RETURN d.status AS status", Map.of("id", ref.id())).list();
            return records.isEmpty() ? Optional.empty() : Optional.of(records.get(0).get("status").asString());
        }
    }

    public void record(DependencyRef ref, String status, String approvedBy, List<String> reasons, String license,
                       String requestedByAgent, String missionId, String jobId) {
        var params = new HashMap<String, Object>();
        params.put("id", ref.id());
        params.put("ecosystem", ref.ecosystem());
        params.put("name", ref.name());
        params.put("version", ref.version());
        params.put("status", status);
        params.put("approvedBy", approvedBy);
        params.put("reasons", reasons == null ? List.of() : reasons);
        params.put("license", license);
        params.put("agent", requestedByAgent);
        params.put("missionId", missionId);
        params.put("jobId", jobId);
        params.put("now", Instant.now().toString());
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (d:Dependency {id:$id}) ON CREATE SET d.createdAt=$now "
                        + "SET d.ecosystem=$ecosystem, d.name=$name, d.version=$version, d.status=$status, "
                        + "d.approvedBy=$approvedBy, d.reasons=$reasons, d.license=$license, d.requestedByAgent=$agent, "
                        + "d.missionId=$missionId, d.jobId=$jobId, "
                        + "d.decidedAt = CASE WHEN $status = 'PENDING_APPROVAL' THEN null ELSE $now END", params);
                return null;
            });
        }
    }

    public void decide(String id, String status, String approvedBy) {
        var params = new HashMap<String, Object>();
        params.put("id", id);
        params.put("status", status);
        params.put("approvedBy", approvedBy);
        params.put("now", Instant.now().toString());
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (d:Dependency {id:$id}) SET d.status=$status, d.approvedBy=$approvedBy, d.decidedAt=$now", params);
                return null;
            });
        }
    }

    public Optional<Map<String, Object>> find(String id) {
        try (var session = driver.session()) {
            var records = session.run("MATCH (d:Dependency {id:$id}) RETURN d", Map.of("id", id)).list();
            return records.isEmpty() ? Optional.empty() : Optional.of(records.get(0).get("d").asMap());
        }
    }

    public List<Map<String, Object>> list() {
        try (var session = driver.session()) {
            return session.run("MATCH (d:Dependency) RETURN d ORDER BY d.createdAt DESC").list(r -> r.get("d").asMap());
        }
    }
}

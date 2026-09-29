package com.aicompany.core.service;

import com.aicompany.core.model.OrchestratorRun;
import com.aicompany.core.model.OrchestratorStatus;
import com.aicompany.core.model.OrchestratorStep;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Neo4j del orquestador (spec 2026-09-28): (:OrchestratorRun)-[:HAS_STEP]->(:OrchestratorStep) inmutable. */
@Service
public class OrchestratorMemoryService {

    private final Driver driver;

    public OrchestratorMemoryService(Driver driver) {
        this.driver = driver;
    }

    public OrchestratorRun create() {
        var now = Instant.now();
        var run = new OrchestratorRun("ORCHESTRATOR-RUN-" + UUID.randomUUID(), OrchestratorStatus.CHOOSING, null, null,
                null, null, now, now, null);
        write("CREATE (r:OrchestratorRun {id:$id}) SET r += $props", Map.of("id", run.id(), "props", props(run)));
        return run;
    }

    public void save(OrchestratorRun run) {
        write("MATCH (r:OrchestratorRun {id:$id}) SET r += $props", Map.of("id", run.id(), "props", props(run)));
    }

    public List<OrchestratorRun> active() {
        try (var session = driver.session()) {
            return session.run("MATCH (r:OrchestratorRun) WHERE r.status IN ['CHOOSING','DISCOVERING','PROPOSING','BUILDING'] "
                    + "RETURN r ORDER BY r.startedAt").list(OrchestratorMemoryService::run);
        }
    }

    /** Productos cuyo ciclo falló desde {@code since} (el orquestador no los vuelve a elegir enseguida). */
    public java.util.Set<String> failedProductsSince(Instant since) {
        try (var session = driver.session()) {
            return new java.util.HashSet<>(session.run("MATCH (r:OrchestratorRun {status:'FAILED'}) "
                            + "WHERE r.productId IS NOT NULL AND datetime(r.updatedAt) >= datetime($since) RETURN r.productId AS id",
                    Map.of("since", since.toString())).list(r -> r.get("id").asString()));
        }
    }

    /** Estados de los últimos {@code limit} ciclos terminados que arrancaron desde {@code since}, del más reciente al más viejo. */
    public List<OrchestratorStatus> finishedStatusesSince(Instant since, int limit) {
        try (var session = driver.session()) {
            return session.run("MATCH (r:OrchestratorRun) WHERE NOT r.status IN ['CHOOSING','DISCOVERING','PROPOSING','BUILDING'] "
                            + "AND datetime(r.startedAt) >= datetime($since) RETURN r.status AS status "
                            + "ORDER BY r.startedAt DESC LIMIT $limit",
                    Map.of("since", since.toString(), "limit", limit))
                    .list(r -> OrchestratorStatus.valueOf(r.get("status").asString()));
        }
    }

    public Optional<OrchestratorRun> latest() {
        try (var session = driver.session()) {
            return session.run("MATCH (r:OrchestratorRun) RETURN r ORDER BY r.startedAt DESC LIMIT 1")
                    .list(OrchestratorMemoryService::run).stream().findFirst();
        }
    }

    public void addStep(String runId, String step, String detail) {
        write("MATCH (r:OrchestratorRun {id:$id}) CREATE (r)-[:HAS_STEP]->(:OrchestratorStep {at:$at, step:$step, detail:$detail})",
                Map.of("id", runId, "at", Instant.now().toString(), "step", step, "detail", detail == null ? "" : detail));
    }

    public List<OrchestratorStep> steps(String runId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:OrchestratorRun {id:$id})-[:HAS_STEP]->(s:OrchestratorStep) RETURN s ORDER BY s.at",
                    Map.of("id", runId)).list(r -> {
                var s = r.get("s");
                return new OrchestratorStep(Instant.parse(s.get("at").asString()), s.get("step").asString(),
                        s.get("detail").asString(""));
            });
        }
    }

    private static OrchestratorRun run(Record r) {
        var n = r.get("r");
        return new OrchestratorRun(n.get("id").asString(), OrchestratorStatus.valueOf(n.get("status").asString()),
                n.get("productId").asString(null), n.get("discoveryMissionId").asString(null),
                n.get("buildMissionId").asString(null), n.get("choiceReason").asString(null),
                Instant.parse(n.get("startedAt").asString()), Instant.parse(n.get("updatedAt").asString()),
                n.get("failureReason").asString(null));
    }

    private static Map<String, Object> props(OrchestratorRun run) {
        var m = new HashMap<String, Object>();
        m.put("status", run.status().name());
        m.put("productId", run.productId());
        m.put("discoveryMissionId", run.discoveryMissionId());
        m.put("buildMissionId", run.buildMissionId());
        m.put("choiceReason", run.choiceReason());
        m.put("startedAt", run.startedAt().toString());
        m.put("updatedAt", run.updatedAt().toString());
        m.put("failureReason", run.failureReason());
        return m;
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

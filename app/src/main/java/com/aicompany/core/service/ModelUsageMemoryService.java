package com.aicompany.core.service;

import com.aicompany.core.model.TokenUsage;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Costo real por misión (2026-10-04): una (:ModelUsage) por llamada remota; con misión, ligada por HAS_MODEL_USAGE. */
@Service
public class ModelUsageMemoryService {

    public record Row(String provider, String model, long calls, long input, long cached, long output, double costUsd) {
    }

    private final Driver driver;

    public ModelUsageMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void save(String missionId, String taskId, String actor, String operation, String provider, String model,
                     TokenUsage usage, double costUsd) {
        var params = new HashMap<String, Object>();
        params.put("missionId", missionId);
        params.put("taskId", taskId);
        params.put("actor", actor);
        params.put("operation", operation);
        params.put("provider", provider);
        params.put("model", model);
        params.put("input", usage.input());
        params.put("cached", usage.cached());
        params.put("output", usage.output());
        params.put("cost", costUsd);
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        CREATE (u:ModelUsage {missionId:$missionId, taskId:$taskId, actor:$actor, operation:$operation,
                          provider:$provider, model:$model, inputTokens:$input, cachedTokens:$cached,
                          outputTokens:$output, costUsd:$cost, at:datetime()})
                        WITH u OPTIONAL MATCH (m:Mission {id:$missionId})
                        FOREACH (_ IN CASE WHEN m IS NULL THEN [] ELSE [1] END | CREATE (m)-[:HAS_MODEL_USAGE]->(u))
                        """, params);
                return null;
            });
        }
    }

    public List<Row> byMission(String missionId) {
        return rows("MATCH (u:ModelUsage {missionId:$id})", Map.of("id", missionId));
    }

    /** Desde el primer día del mes en curso (UTC). */
    public List<Row> thisMonth() {
        return rows("MATCH (u:ModelUsage) WHERE u.at >= datetime({year: date().year, month: date().month, day: 1})",
                Map.of());
    }

    private List<Row> rows(String match, Map<String, Object> params) {
        try (var session = driver.session()) {
            return session.run(match + """
                             RETURN u.provider AS provider, u.model AS model, count(u) AS calls,
                              sum(u.inputTokens) AS input, sum(u.cachedTokens) AS cached, sum(u.outputTokens) AS output,
                              sum(u.costUsd) AS cost ORDER BY cost DESC, calls DESC
                            """, params)
                    .list(r -> new Row(r.get("provider").asString(), r.get("model").asString(), r.get("calls").asLong(),
                            r.get("input").asLong(), r.get("cached").asLong(), r.get("output").asLong(),
                            r.get("cost").asDouble()));
        }
    }
}

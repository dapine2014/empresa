package com.aicompany.core.delivery;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Nodos {@code (:Delivery {id: missionId-INC-n})}; los targets viajan como {@code targetsJson}. */
@Service
public class DeliveryMemoryService {

    private static final String RETURN = " RETURN d.missionId AS missionId, d.increment AS increment, d.status AS status, "
            + "d.path AS path, d.sizeBytes AS sizeBytes, d.targetsJson AS targetsJson, d.error AS error, "
            + "d.createdAt AS createdAt";

    private final Driver driver;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeliveryMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void markPackaging(String missionId, int increment) {
        save(new DeliveryRecord(missionId, increment, "PACKAGING", "", 0, List.of(), "", Instant.now().toString()));
    }

    public void save(DeliveryRecord r) {
        var params = new HashMap<String, Object>();
        params.put("id", id(r.missionId(), r.increment()));
        params.put("mission", r.missionId());
        params.put("inc", r.increment());
        params.put("status", r.status());
        params.put("path", r.path() == null ? "" : r.path());
        params.put("size", r.sizeBytes());
        params.put("targets", mapper.writeValueAsString(r.targets() == null ? List.of() : r.targets()));
        params.put("error", r.error() == null ? "" : r.error());
        params.put("at", r.createdAt() == null ? Instant.now().toString() : r.createdAt());
        boolean written;
        try (var session = driver.session()) {
            written = session.executeWrite(tx -> tx.run("MATCH (m:Mission {id:$mission}) MERGE (d:Delivery {id:$id}) "
                    + "MERGE (m)-[:HAS_DELIVERY]->(d) SET d.missionId=$mission, d.increment=$inc, "
                    + "d.status=$status, d.path=$path, d.sizeBytes=$size, d.targetsJson=$targets, d.error=$error, "
                    + "d.createdAt=$at RETURN d.id AS id", params).hasNext());
        }
        if (!written) {
            throw new IllegalStateException("La misión " + r.missionId() + " no existe: no se registró la entrega.");
        }
    }

    public List<DeliveryRecord> list(String missionId) {
        return query("MATCH (:Mission {id:$m})-[:HAS_DELIVERY]->(d:Delivery)" + RETURN + " ORDER BY d.increment DESC", Map.of("m", missionId));
    }

    public Optional<DeliveryRecord> find(String missionId, int increment) {
        return query("MATCH (:Mission {id:$m})-[:HAS_DELIVERY]->(d:Delivery {id:$id})" + RETURN, Map.of("m", missionId, "id", id(missionId, increment))).stream().findFirst();
    }

    public List<DeliveryRecord> packaging() {
        return query("MATCH (d:Delivery {status:'PACKAGING'})" + RETURN, Map.of());
    }

    public List<DeliveryRecord> recent(int limit) {
        return query("MATCH (d:Delivery)" + RETURN + " ORDER BY d.createdAt DESC LIMIT $limit",
                Map.of("limit", Math.max(1, limit)));
    }

    private List<DeliveryRecord> query(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            return session.run(cypher, params).list(this::map);
        }
    }

    private DeliveryRecord map(Record r) {
        List<DeliveryFacts.TargetResult> targets = List.of();
        var json = r.get("targetsJson").asString("");
        if (!json.isBlank()) {
            targets = mapper.readValue(json, new TypeReference<List<DeliveryFacts.TargetResult>>() { });
        }
        return new DeliveryRecord(r.get("missionId").asString(""), r.get("increment").asInt(0), r.get("status").asString(""),
                r.get("path").asString(""), r.get("sizeBytes").asLong(0), targets, r.get("error").asString(""),
                r.get("createdAt").asString(""));
    }

    private static String id(String missionId, int increment) {
        return missionId + "-INC-" + increment;
    }
}

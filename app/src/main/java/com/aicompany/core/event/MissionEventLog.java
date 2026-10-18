package com.aicompany.core.event;

import com.aicompany.core.model.MissionEventView;
import com.aicompany.core.service.CompanyMemoryService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bitácora (spec 2026-10-17): cada evento EMPRESA_* con misión queda en (:Mission)-[:HAS_EVENT]->(:MissionEvent), con
 * los últimos 500 por misión. Nunca frena el evento: si Neo4j falla, se loguea y Kafka lo recibe igual.
 */
@Service
public class MissionEventLog {

    private static final Logger log = LoggerFactory.getLogger(MissionEventLog.class);
    public static final int MAX_EVENTS_PER_MISSION = 500;
    // Formato fijo de 9 decimales en UTC: Instant.toString() varía de largo y rompería el orden lexicográfico (ORDER BY, SKIP).
    private static final DateTimeFormatter AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);

    private final Driver driver;
    private final CompanyMemoryService companyMemory;

    public MissionEventLog(Driver driver, CompanyMemoryService companyMemory) {
        this.driver = driver;
        this.companyMemory = companyMemory;
    }

    public void record(CompanyEvent event) {
        if (event == null || event.missionId() == null) {
            return;
        }
        try {
            var name = displayName(agentNames(), event.agentId(), event.eventType());
            var params = new HashMap<String, Object>();
            params.put("mission", event.missionId());
            params.put("id", event.eventId());
            params.put("type", event.eventType());
            params.put("at", AT_FORMAT.format(event.timestamp()));
            params.put("agentId", event.agentId());
            params.put("taskId", event.taskId());
            params.put("summary", MissionEventText.summary(event.eventType(), name, event.data()));
            params.put("round", roundOf(event.data()));
            params.put("increment", incrementOf(event.data()));
            params.put("max", MAX_EVENTS_PER_MISSION);
            try (var session = driver.session()) {
                session.executeWrite(tx -> {
                    tx.run("MATCH (m:Mission {id:$mission}) CREATE (m)-[:HAS_EVENT]->(:MissionEvent {id:$id, "
                            + "missionId:$mission, type:$type, at:$at, agentId:$agentId, taskId:$taskId, "
                            + "summary:$summary, round:$round, increment:$increment})", params).consume();
                    // Tope por misión, en la misma transacción.
                    // Los INCREMENT_STARTED nunca se borran por tope: el digest los necesita para acotar el incremento.
                    tx.run("MATCH (:Mission {id:$mission})-[:HAS_EVENT]->(e:MissionEvent) "
                            + "WHERE e.type <> 'EMPRESA_INCREMENT_STARTED' WITH e ORDER BY e.at DESC "
                            + "SKIP $max DETACH DELETE e", params).consume();
                    return null;
                });
            }
        } catch (Exception ex) {
            log.warn("MISSION_EVENT_LOG_FAILED mission={} type={} error={}", event.missionId(), event.eventType(),
                    ex.getMessage());
        }
    }

    public List<MissionEventView> recent(String missionId, int limit) {
        var names = agentNames();
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_EVENT]->(e:MissionEvent) RETURN e ORDER BY e.at DESC "
                    + "LIMIT $limit", Map.of("id", missionId, "limit", limit)).list(r -> view(r, names));
        }
    }

    /** Todos los EMPRESA_INCREMENT_STARTED de la misión (el tope de 500 no los borra), más reciente primero. */
    public List<MissionEventView> incrementStarts(String missionId) {
        var names = agentNames();
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_EVENT]->(e:MissionEvent "
                    + "{type:'EMPRESA_INCREMENT_STARTED'}) RETURN e ORDER BY e.at DESC",
                    Map.of("id", missionId)).list(r -> view(r, names));
        }
    }

    public List<MissionEventView> recentAll(int limit) {
        var names = agentNames();
        try (var session = driver.session()) {
            return session.run("MATCH (e:MissionEvent) RETURN e ORDER BY e.at DESC LIMIT $limit",
                    Map.of("limit", limit)).list(r -> view(r, names));
        }
    }

    private MissionEventView view(Record r, Map<String, String> names) {
        var e = r.get("e");
        var agentId = e.get("agentId").isNull() ? null : e.get("agentId").asString();
        return new MissionEventView(e.get("id").asString(), e.get("type").asString(),
                Instant.parse(e.get("at").asString()), agentId, displayName(names, agentId, e.get("type").asString()),
                e.get("taskId").isNull() ? null : e.get("taskId").asString(), e.get("summary").asString(),
                e.get("round").isNull() ? null : e.get("round").asInt(),
                e.get("increment").isNull() ? null : e.get("increment").asInt());
    }

    /** Una sola consulta por llamada; nunca lanza (vacío si Neo4j falla). */
    private Map<String, String> agentNames() {
        try {
            var names = new HashMap<String, String>();
            for (var a : companyMemory.agents()) {
                names.put(String.valueOf(a.get("id")), String.valueOf(a.get("name")));
            }
            return names;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** El nombre cae al id si no está en el mapa. */
    static String nameOf(Map<String, String> names, String agentId) {
        return agentId == null ? null : names.getOrDefault(agentId, agentId);
    }

    /** Bitácora (spec 2026-10-17): los ids que no son del roster (system, human, sandbox) y el ceo que actualiza la misión se muestran «Forjai». */
    static String displayName(Map<String, String> names, String agentId, String eventType) {
        if (agentId == null) {
            return null;
        }
        if (java.util.Set.of("system", "human", "sandbox").contains(agentId)
                || ("ceo".equals(agentId) && "EMPRESA_MISSION_UPDATED".equals(eventType))) {
            return "Forjai";
        }
        return nameOf(names, agentId);
    }

    public static Integer roundOf(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        for (var key : List.of("round", "repairRound", "testTriageRound")) {
            if (data.get(key) instanceof Number n) {
                return n.intValue();
            }
        }
        return null;
    }

    public static Integer incrementOf(Map<String, Object> data) {
        return data != null && data.get("increment") instanceof Number n ? n.intValue() : null;
    }
}

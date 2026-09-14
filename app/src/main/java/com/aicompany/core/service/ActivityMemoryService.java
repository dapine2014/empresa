package com.aicompany.core.service;

import com.aicompany.core.model.ActivityItem;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Línea de tiempo de "Activity" para el Command Center web — derivada de
 * timestamps que Neo4j ya guarda por otras razones (Mission/AgentTask/
 * Evidence/Decision), no de un consumer de Kafka nuevo. Deliberado: este
 * proyecto nunca consumió Kafka (solo produce vía
 * {@code CompanyEventPublisher}), y agregar un primer consumer solo para
 * una lista de actividad reciente sería infraestructura nueva no trivial
 * para lo que en el fondo son los mismos cambios de estado que ya
 * quedan escritos en el grafo por otras razones — mismo espíritu que
 * "polling simple, sin infraestructura nueva" que ya se usa para el
 * refresco del frontend.
 */
@Service
public class ActivityMemoryService {

    private final Driver driver;

    public ActivityMemoryService(Driver driver) {
        this.driver = driver;
    }

    public List<ActivityItem> recent(int limit) {
        try (var session = driver.session()) {
            return session.run("""
                            CALL {
                              MATCH (t:AgentTask)
                              RETURN 'TASK' AS type, t.missionId AS missionId, t.agentId AS agentId,
                                     (t.agentId + ' - ' + t.action + ': ' + t.status) AS description,
                                     t.updatedAt AS timestamp
                              UNION ALL
                              MATCH (m:Mission)
                              RETURN 'MISSION' AS type, m.id AS missionId, null AS agentId,
                                     (coalesce(m.currentStep, '') + ': ' + m.status) AS description,
                                     m.updatedAt AS timestamp
                              UNION ALL
                              MATCH (e:Evidence)
                              RETURN 'EVIDENCE' AS type, e.missionId AS missionId, e.agentId AS agentId,
                                     left(e.description, 120) AS description,
                                     e.updatedAt AS timestamp
                              UNION ALL
                              MATCH (d:Decision)
                              RETURN 'DECISION' AS type, d.missionId AS missionId, 'human' AS agentId,
                                     (d.decision + ': ' + left(d.reasoning, 100)) AS description,
                                     d.decidedAt AS timestamp
                            }
                            RETURN type, missionId, agentId, description, timestamp
                            ORDER BY timestamp DESC
                            LIMIT $limit
                            """,
                            Map.of("limit", limit))
                    .list(r -> new ActivityItem(
                            r.get("type").asString(),
                            r.get("missionId").isNull() ? null : r.get("missionId").asString(),
                            r.get("agentId").isNull() ? null : r.get("agentId").asString(),
                            r.get("description").asString(),
                            Instant.parse(r.get("timestamp").asString())
                    ));
        }
    }
}

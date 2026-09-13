package com.aicompany.core.service;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MissionMemoryService {
    private final Driver driver;

    public MissionMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void ensureMission(String missionId, String instruction) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (m:Mission {id:$id}) SET m.name=$name, m.instruction=$instruction, m.status='CREATED', m.progress=0, m.currentStep='Creada', m.message='Misión recibida', m.updatedAt=$updatedAt",
                        Map.of("id", missionId,
                                "name", missionId.equals("MISSION-001") ? "MISSION-001 — Descubrimiento del primer negocio" : missionId,
                                "instruction", instruction,
                                "updatedAt", Instant.now().toString()));
                tx.run("MATCH (m:Mission {id:$id}), (c:Company {id:'AI-COMPANY'}) MERGE (c)-[:HAS_MISSION]->(m)", Map.of("id", missionId));
                tx.run("MATCH (m:Mission {id:$id}), (a:Agent {id:'ceo'}) MERGE (m)-[:LED_BY]->(a)", Map.of("id", missionId));
                return null;
            });
        }
    }

    public void updateMission(String missionId, MissionStatus status, int progress, String currentStep, String message) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (m:Mission {id:$id}) SET m.status=$status, m.progress=$progress, m.currentStep=$step, m.message=$message, m.updatedAt=$updatedAt",
                        Map.of("id", missionId, "status", status.name(), "progress", progress,
                                "step", currentStep, "message", message, "updatedAt", Instant.now().toString()));
                return null;
            });
        }
    }

    public void createTask(String taskId, String missionId, String agentId, String action) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (m:Mission {id:$missionId}), (a:Agent {id:$agentId}) " +
                                "MERGE (t:AgentTask {id:$taskId}) SET t.missionId=$missionId, t.agentId=$agentId, " +
                                "t.action=$action, t.status='PENDING', t.updatedAt=$updatedAt " +
                                "MERGE (m)-[:HAS_TASK]->(t) MERGE (a)-[:ASSIGNED_TASK]->(t)",
                        Map.of("taskId", taskId, "missionId", missionId, "agentId", agentId,
                                "action", action, "updatedAt", Instant.now().toString()));
                return null;
            });
        }
    }

    public void updateTask(String taskId, String status, String result) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (t:AgentTask {id:$id}) SET t.status=$status, t.result=$result, t.updatedAt=$updatedAt",
                        Map.of("id", taskId, "status", status, "result", result == null ? "" : result,
                                "updatedAt", Instant.now().toString()));
                return null;
            });
        }
    }

    public Optional<MissionResponse> find(String missionId) {
        try (var session = driver.session()) {
            var records = session.run("MATCH (m:Mission {id:$id}) RETURN m.status AS status, m.progress AS progress, m.currentStep AS step, m.message AS message, m.updatedAt AS updatedAt", Map.of("id", missionId)).list();
            return records.stream().findFirst().map(r -> new MissionResponse(
                    missionId,
                    MissionStatus.valueOf(r.get("status").asString()),
                    r.get("progress").asInt(),
                    r.get("step").asString(),
                    r.get("message").asString(),
                    Instant.parse(r.get("updatedAt").asString())
            ));
        }
    }

    public List<AgentTask> tasks(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (t:AgentTask {missionId:$missionId}) RETURN t.id AS id, t.agentId AS agentId, t.action AS action, t.status AS status, t.result AS result, t.updatedAt AS updatedAt ORDER BY t.id",
                            Map.of("missionId", missionId))
                    .list(r -> new AgentTask(
                            r.get("id").asString(), missionId,
                            r.get("agentId").asString(), r.get("action").asString(), r.get("status").asString(),
                            r.get("result").asString(""), Instant.parse(r.get("updatedAt").asString())));
        }
    }
}

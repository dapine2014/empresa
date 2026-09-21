package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.evidence.EvidenceDedupKey;
import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.InvestorDecision;
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

    /**
     * Migración idempotente, para las misiones que ya existían antes de
     * que {@code Mission.environment} existiera: {@code MISSION-001} es
     * la misión fundacional real (`docs/MISSION-001.md`, "descubrimiento
     * del primer negocio"), no una prueba de desarrollo — se marca
     * {@code PRODUCTION} explícitamente. El resto de misiones viejas sin
     * {@code environment} (todo el histórico real de sesiones de
     * depuración: {@code MISSION-STRUCTURED-*}, {@code MVP-*},
     * {@code MISSION-DEBUG-*}, etc.) no necesita una escritura explícita
     * — {@link #find}/{@link #findAll} ya las clasifican como
     * {@code TEST} vía {@code coalesce(m.environment, 'TEST')} al leer.
     */
    public void backfillMissionEnvironment() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (m:Mission {id:'MISSION-001'}) WHERE m.environment IS NULL SET m.environment='PRODUCTION'");
                return null;
            });
        }
    }

    public void ensureMission(String missionId, String instruction, String environment) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (m:Mission {id:$id}) SET m.name=$name, m.instruction=$instruction, m.environment=$environment, m.status='CREATED', m.progress=0, m.currentStep='Creada', m.message='Misión recibida', m.updatedAt=$updatedAt",
                        Map.of("id", missionId,
                                "name", missionId.equals("MISSION-001") ? "MISSION-001 — Descubrimiento del primer negocio" : missionId,
                                "instruction", instruction,
                                "environment", environment,
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

    /**
     * Decisión real del inversionista humano sobre una misión (nunca
     * generada por un agente ni por el CEO) — primera entidad real del
     * grupo "Company Memory funcional" (`EMPRESA_AI_NUEVO_TODO_EVIDENCE.md`
     * §34, antes solo un constraint sin ningún nodo). Un mismo
     * {@code missionId} puede tener varias decisiones a lo largo del
     * tiempo (p. ej. "pide más evidencia" antes de un "aprueba" final);
     * por eso {@code decisionId} incluye un timestamp, no es
     * {@code MERGE}-idempotente sobre el mismo id como sí lo son los
     * registros de evidencia.
     */
    public void recordDecision(
            String missionId,
            String decisionId,
            InvestorDecision decision,
            String reasoning) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (m:Mission {id:$missionId}) " +
                                "MERGE (d:Decision {id:$decisionId}) " +
                                "SET d.missionId=$missionId, d.decision=$decision, " +
                                "d.reasoning=$reasoning, d.decidedAt=$now " +
                                "MERGE (m)-[:HAS_DECISION]->(d)",
                        Map.of(
                                "missionId", missionId,
                                "decisionId", decisionId,
                                "decision", decision.name(),
                                "reasoning", reasoning,
                                "now", Instant.now().toString()
                        ));
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
                                "MERGE (m)-[:HAS_TASK]->(t) MERGE (a)-[:ASSIGNED_TASK]->(t) " +
                                "MERGE (m)-[:INVOLVES_AGENT]->(a)",
                        Map.of("taskId", taskId, "missionId", missionId, "agentId", agentId,
                                "action", action, "updatedAt", Instant.now().toString()));
                return null;
            });
        }
    }

    /**
     * Estado propio del agente ({@code WORKING}/{@code IDLE}) — propiedad
     * real en el nodo {@code Agent}, escrita por {@code AgentRuntime} en
     * cada transición (no calculada al vuelo desde la última
     * {@code AgentTask}). Antes de esto, el endpoint de estado exponía el
     * status de la tarea como si fuera el del agente: un agente con su
     * última tarea en {@code COMPLETED} se veía "trabajando" para
     * siempre, tanto en el chat como en la pantalla Agents — reportado
     * por el usuario con captura de pantalla real.
     *
     * <p>Límite conocido, no resuelto: si el proceso se reinicia con un
     * agente realmente {@code WORKING} (misión en curso), no hay
     * reconciliación — el nodo queda con ese valor stale hasta la
     * próxima tarea real de ese agente. Mismo criterio que otros límites
     * ya documentados en el proyecto (p. ej. DNS rebinding en
     * `WebPageFetcher`): conocido, aceptado, no bloqueante para el MVP.
     */
    public void setAgentStatus(String agentId, String status) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (a:Agent {id:$agentId}) SET a.status=$status",
                        Map.of("agentId", agentId, "status", status));
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

    /**
     * Instrucción original de la misión, ya persistida desde
     * {@link #ensureMission} pero nunca expuesta hasta ahora — la
     * necesita {@code MissionService.recordDecision} para poder arrancar
     * la fase de desarrollo tras un `APPROVE` sin que el llamador (el
     * fundador, vía chat o el endpoint de decisión) tenga que repetirla.
     */
    public Optional<String> instruction(String missionId) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (m:Mission {id:$id}) RETURN coalesce(m.instruction, '') AS instruction",
                            Map.of("id", missionId))
                    .list(r -> r.get("instruction").asString())
                    .stream().findFirst();
        }
    }

    public Optional<MissionResponse> find(String missionId) {
        try (var session = driver.session()) {
            var records = session.run("MATCH (m:Mission {id:$id}) RETURN m.status AS status, coalesce(m.environment, 'TEST') AS environment, m.progress AS progress, m.currentStep AS step, m.message AS message, m.updatedAt AS updatedAt", Map.of("id", missionId)).list();
            return records.stream().findFirst().map(r -> new MissionResponse(
                    missionId,
                    MissionStatus.valueOf(r.get("status").asString()),
                    r.get("environment").asString(),
                    r.get("progress").asInt(),
                    r.get("step").asString(),
                    r.get("message").asString(),
                    Instant.parse(r.get("updatedAt").asString())
            ));
        }
    }

    /**
     * Trae el dato real y actual de un conjunto puntual de misiones por
     * id — usado por {@code ChatIntentRouter} para resolver referencias
     * conversacionales ("esas"/"esos") contra el estado real de las
     * misiones mencionadas, nunca contra el texto de una respuesta
     * anterior que puede estar desactualizado.
     */
    public List<MissionResponse> findByIds(List<String> missionIds) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (m:Mission) WHERE m.id IN $ids RETURN m.id AS id, m.status AS status, " +
                                    "coalesce(m.environment, 'TEST') AS environment, " +
                                    "m.progress AS progress, m.currentStep AS step, " +
                                    "m.message AS message, m.updatedAt AS updatedAt",
                            Map.of("ids", missionIds))
                    .list(r -> new MissionResponse(
                            r.get("id").asString(),
                            MissionStatus.valueOf(r.get("status").asString()),
                            r.get("environment").asString(),
                            r.get("progress").asInt(),
                            r.get("step").asString(),
                            r.get("message").asString(),
                            Instant.parse(r.get("updatedAt").asString())
                    ));
        }
    }

    /**
     * Misiones más recientes (no filtra por estado) — base del panel
     * "Missions" del Command Center web. {@code limit} fijo desde el
     * llamador (v1 no expone paginación).
     */
    public List<MissionResponse> findAll(int limit) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (m:Mission) RETURN m.id AS id, m.status AS status, " +
                                    "coalesce(m.environment, 'TEST') AS environment, " +
                                    "m.progress AS progress, m.currentStep AS step, " +
                                    "m.message AS message, m.updatedAt AS updatedAt " +
                                    "ORDER BY m.updatedAt DESC LIMIT $limit",
                            Map.of("limit", limit))
                    .list(r -> new MissionResponse(
                            r.get("id").asString(),
                            MissionStatus.valueOf(r.get("status").asString()),
                            r.get("environment").asString(),
                            r.get("progress").asInt(),
                            r.get("step").asString(),
                            r.get("message").asString(),
                            Instant.parse(r.get("updatedAt").asString())
                    ));
        }
    }

    /**
     * Estado real de cada {@code Agent} (hoy 9: ceo + los 5 delegados + los 3 nuevos del Engineering Team) —
     * base del panel "Agents" del Command Center web. {@code status} es
     * la propiedad propia del nodo {@code Agent} ({@code WORKING}/
     * {@code IDLE}, ver {@link #setAgentStatus}) — deliberadamente
     * distinta de {@code taskStatus}, el status de su {@code AgentTask}
     * más reciente por {@code updatedAt} en cualquier misión (p. ej.
     * {@code COMPLETED}/{@code FAILED}). Antes se exponía el status de la
     * tarea como si fuera el del agente.
     */
    public List<AgentStatusResponse> latestTaskPerAgent() {
        try (var session = driver.session()) {
            return session.run("""
                            MATCH (a:Agent)
                            OPTIONAL MATCH (a)-[:ASSIGNED_TASK]->(t:AgentTask)
                            WITH a, t ORDER BY t.updatedAt DESC
                            WITH a, collect(t)[0] AS latest
                            RETURN a.id AS agentId, a.name AS name, a.role AS role, a.personality AS personality,
                                   coalesce(a.status, 'IDLE') AS status,
                                   latest.status AS taskStatus,
                                   latest.missionId AS missionId, latest.action AS action,
                                   latest.updatedAt AS updatedAt
                            ORDER BY a.id
                            """)
                    .list(r -> new AgentStatusResponse(
                            r.get("agentId").asString(),
                            r.get("name").asString(),
                            r.get("role").asString(),
                            r.get("personality").asString(),
                            r.get("status").asString(),
                            r.get("missionId").isNull() ? null : r.get("missionId").asString(),
                            r.get("action").isNull() ? null : r.get("action").asString(),
                            r.get("taskStatus").isNull() ? null : r.get("taskStatus").asString(),
                            r.get("updatedAt").isNull() ? null : Instant.parse(r.get("updatedAt").asString())
                    ));
        }
    }

    /**
     * Registra la evidencia de una tarea como nodos {@code Evidence} de
     * primera clase, enlazados a su {@code AgentTask} — no solo como texto
     * dentro del blob JSON de {@code t.result}. Es la parte de persistencia
     * del "Evidence Engine": permite que la evidencia acumulada por la
     * empresa se pueda consultar/auditar independientemente de la tarea
     * puntual que la generó.
     *
     * <p>Deduplicación (`EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §17): el id del
     * nodo ya no es {@code "{taskId}-EVIDENCE-{índice}"} (garantizaba un
     * nodo nuevo por tarea, aunque dos agentes citaran exactamente la misma
     * fuente) sino {@link EvidenceDedupKey#stableId}, una clave estable
     * derivada de {@code source}+{@code description} normalizados. Si dos
     * tareas citan la misma evidencia, comparten el mismo nodo — cada una
     * igual gana su propia relación {@code (:AgentTask)-[:HAS_EVIDENCE]}
     * hacia él (así se puede consultar cuántos agentes corroboraron un
     * mismo dato), pero el contenido del nodo (`missionId`/`agentId`/
     * `description`/`source`/`sourceType`/`verified`) solo se fija en la
     * primera escritura ({@code ON CREATE SET}) — las siguientes citas de
     * la misma evidencia no lo pisan, solo refrescan {@code updatedAt}.
     */
    public void recordEvidence(
            String taskId,
            String missionId,
            String agentId,
            List<AgentResult.Evidence> evidenceList) {

        if (evidenceList == null || evidenceList.isEmpty()) {
            return;
        }

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                for (var evidence : evidenceList) {

                    if (evidence == null) {
                        continue;
                    }

                    tx.run("MATCH (t:AgentTask {id:$taskId}) " +
                                    "MERGE (e:Evidence {id:$evidenceId}) " +
                                    "ON CREATE SET e.missionId=$missionId, e.agentId=$agentId, " +
                                    "e.description=$description, e.source=$source, " +
                                    "e.sourceType=$sourceType, e.verified=$verified, " +
                                    "e.createdAt=$updatedAt, e.updatedAt=$updatedAt " +
                                    "ON MATCH SET e.updatedAt=$updatedAt " +
                                    "MERGE (t)-[:HAS_EVIDENCE]->(e)",
                            Map.of(
                                    "taskId", taskId,
                                    "evidenceId", EvidenceDedupKey.stableId(
                                            evidence.source(), evidence.description()),
                                    "missionId", missionId,
                                    "agentId", agentId,
                                    "description", evidence.description() == null ? "" : evidence.description(),
                                    "source", evidence.source() == null ? "" : evidence.source(),
                                    "sourceType", evidence.sourceType() == null ? "" : evidence.sourceType(),
                                    "verified", evidence.verified(),
                                    "updatedAt", Instant.now().toString()
                            ));
                }

                return null;
            });
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

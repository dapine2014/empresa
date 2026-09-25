package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.evidence.EvidenceDedupKey;
import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.FinancialCriteriaCommand;
import com.aicompany.core.model.FinancialCriteriaResponse;
import com.aicompany.core.model.FinancialMetric;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
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

    public void ensureMission(
            String missionId,
            String instruction,
            String environment,
            FinancialCriteriaCommand financialCriteria) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                // Neo4j: asignar `null` a una propiedad la remueve. `ensureMission`
                // se invoca también para "re-arrancar" una misión ya existente
                // (p. ej. "ejecuta MISSION-42" por chat, que siempre llama acá con
                // financialCriteria=null) -- si el SET incluyera las 4 propiedades
                // con valores null incondicionalmente, un re-arranque borraría en
                // silencio un objetivo financiero ya declarado para esa misión
                // (financialCriteria es inmutable una vez creada). Por eso el SET
                // de esas 4 propiedades solo se arma cuando financialCriteria no
                // es null; si es null, ni siquiera se mencionan y quedan como
                // estén (ausentes en una misión nueva, preservadas en una existente).
                var financialCriteriaSet = financialCriteria == null
                        ? ""
                        : ", m.financialCriteriaMetric=$metric, m.financialCriteriaTargetAmount=$targetAmount, "
                                + "m.financialCriteriaCurrency=$currency, m.financialCriteriaDeadline=$deadline";
                tx.run("MERGE (m:Mission {id:$id}) SET m.name=$name, m.instruction=$instruction, "
                                + "m.environment=$environment, m.status='CREATED', m.progress=0, "
                                + "m.currentStep='Creada', m.message='Misión recibida', m.updatedAt=$updatedAt"
                                + financialCriteriaSet,
                        financialCriteriaParams(missionId, instruction, environment, financialCriteria));
                tx.run("MATCH (m:Mission {id:$id}), (c:Company {id:'AI-COMPANY'}) MERGE (c)-[:HAS_MISSION]->(m)", Map.of("id", missionId));
                tx.run("MATCH (m:Mission {id:$id}), (a:Agent {id:'ceo'}) MERGE (m)-[:LED_BY]->(a)", Map.of("id", missionId));
                return null;
            });
        }
    }

    /**
     * {@code Map.of} no admite valores {@code null}, por eso un
     * {@code HashMap} mutable acá. Cuando {@code fc} es {@code null} los 4
     * parámetros de financialCriteria igual se incluyen (Neo4j ignora
     * parámetros no referenciados por la query) pero el SET condicional de
     * {@link #ensureMission} no los usa en ese caso.
     */
    private Map<String, Object> financialCriteriaParams(
            String missionId, String instruction, String environment, FinancialCriteriaCommand fc) {

        var params = new HashMap<String, Object>();
        params.put("id", missionId);
        params.put("name", missionId.equals("MISSION-001") ? "MISSION-001 — Descubrimiento del primer negocio" : missionId);
        params.put("instruction", instruction);
        params.put("environment", environment);
        params.put("updatedAt", Instant.now().toString());
        params.put("metric", fc == null ? null : fc.metric().name());
        params.put("targetAmount", fc == null ? null : fc.targetAmount());
        params.put("currency", fc == null ? null : fc.currencyOrDefault());
        params.put("deadline", fc == null || fc.deadline() == null ? null : fc.deadline().toString());
        return params;
    }

    private FinancialCriteriaResponse mapFinancialCriteria(org.neo4j.driver.Record r) {
        if (r.get("financialCriteriaMetric").isNull()) {
            return null;
        }
        var deadlineValue = r.get("financialCriteriaDeadline");
        return new FinancialCriteriaResponse(
                FinancialMetric.valueOf(r.get("financialCriteriaMetric").asString()),
                r.get("financialCriteriaTargetAmount").asDouble(),
                r.get("financialCriteriaCurrency").asString(),
                deadlineValue.isNull() ? null : LocalDate.parse(deadlineValue.asString())
        );
    }

    /**
     * Lookup dedicado y liviano para {@code MissionExecutor}/
     * {@code CustomerService} -- separado de {@link #find} para no
     * acoplar sus mocks de test al resto de {@code MissionResponse}.
     */
    public Optional<FinancialCriteriaResponse> financialCriteria(String missionId) {
        try (var session = driver.session()) {
            var records = session.run(
                            "MATCH (m:Mission {id:$id}) RETURN m.financialCriteriaMetric AS financialCriteriaMetric, "
                                    + "m.financialCriteriaTargetAmount AS financialCriteriaTargetAmount, "
                                    + "m.financialCriteriaCurrency AS financialCriteriaCurrency, "
                                    + "m.financialCriteriaDeadline AS financialCriteriaDeadline",
                            Map.of("id", missionId))
                    .list();
            if (records.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(mapFinancialCriteria(records.get(0)));
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

    /**
     * Si la misión tiene clientes o ventas reales registrados por el
     * fundador ({@code CustomerMemoryService}) — datos que no se
     * regeneran re-ejecutando la misión, así que bloquean su borrado.
     */
    public boolean hasRealCustomerData(String missionId) {
        try (var session = driver.session()) {
            return session.run(
                    "MATCH (m:Mission {id:$missionId}) " +
                            "RETURN EXISTS { (m)-[:HAS_CUSTOMER|HAS_TRANSACTION]->() } AS has",
                    Map.of("missionId", missionId)
            ).list().stream().findFirst().map(r -> r.get("has").asBoolean()).orElse(false);
        }
    }

    /**
     * Borra la misión y todo lo que su orquestación colgó de ella, en una
     * sola transacción: {@code AgentTask}, {@code Decision},
     * {@code Opportunity} y sus {@code Customer} LEAD. Las {@code Evidence}
     * solo se borran si quedan huérfanas — por la deduplicación de
     * {@code EvidenceDedupKey} un mismo nodo puede estar citado por tareas
     * de otras misiones; si sobreviven y esta misión las había creado, su
     * {@code missionId} se reasigna a una misión que todavía las cita. Todo
     * se matchea también por la propiedad {@code missionId} (no solo por
     * relaciones), que es lo que usa Activity. Nunca toca {@code Agent}/{@code Company}/prompts/
     * políticas. También saca la misión del foco conversacional
     * ({@code Conversation.lastMentionedIds}) para que el chat no resuelva
     * una referencia a un id que ya no existe.
     */
    public void deleteMission(String missionId) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                var params = Map.<String, Object>of("missionId", missionId);

                // Además de seguir las relaciones, se matchea por la propiedad
                // missionId: Activity (ActivityMemoryService) filtra por esa
                // propiedad, y un nodo cuya relación con la misión se perdió
                // seguiría apareciendo en la línea de tiempo de una misión
                // que ya no existe.
                var evidenceIds = tx.run(
                        "OPTIONAL MATCH (m:Mission {id:$missionId})-[:HAS_TASK]->(:AgentTask)" +
                                "-[:HAS_EVIDENCE]->(te:Evidence) " +
                                "WITH collect(DISTINCT te.id) AS taskEvidence " +
                                "OPTIONAL MATCH (o:Opportunity {missionId:$missionId})-[:HAS_CANDIDATE]->" +
                                "(:Customer {status:'LEAD'})-[:HAS_EVIDENCE]->(ce:Evidence) " +
                                "WITH taskEvidence, collect(DISTINCT ce.id) AS candidateEvidence " +
                                "OPTIONAL MATCH (pe:Evidence {missionId:$missionId}) " +
                                "WITH taskEvidence, candidateEvidence, collect(DISTINCT pe.id) AS propertyEvidence " +
                                "RETURN taskEvidence + candidateEvidence + propertyEvidence AS ids",
                        params
                ).single().get("ids").asList(v -> v.asString());

                tx.run("MATCH (o:Opportunity {missionId:$missionId})-[:HAS_CANDIDATE]->(c:Customer {status:'LEAD'}) " +
                                "WHERE NOT EXISTS { (c)<-[:HAS_CANDIDATE|HAS_CUSTOMER]-(other) WHERE other <> o } " +
                                "DETACH DELETE c",
                        params);

                tx.run("OPTIONAL MATCH (m:Mission {id:$missionId}) " +
                                "OPTIONAL MATCH (m)-[:HAS_TASK|HAS_DECISION|HAS_OPPORTUNITY]->(n) " +
                                "WITH m, collect(DISTINCT n) AS owned " +
                                "FOREACH (n IN owned | DETACH DELETE n) " +
                                "FOREACH (x IN CASE WHEN m IS NULL THEN [] ELSE [m] END | DETACH DELETE x)",
                        params);

                tx.run("MATCH (n) WHERE (n:AgentTask OR n:Decision OR n:Opportunity) " +
                                "AND n.missionId = $missionId " +
                                "DETACH DELETE n",
                        params);

                tx.run("MATCH (e:Evidence) WHERE e.id IN $ids " +
                                "AND NOT EXISTS { ()-[:HAS_EVIDENCE]->(e) } " +
                                "DETACH DELETE e",
                        Map.of("ids", evidenceIds));

                // Evidencia compartida (dedup) que sigue citada por otra
                // misión pero fue creada por esta: se reasigna a una misión
                // que todavía la cita, para que Activity no la muestre bajo
                // un id borrado.
                tx.run("MATCH (e:Evidence {missionId:$missionId})<-[:HAS_EVIDENCE]-(p) " +
                                "WHERE p.missionId IS NOT NULL AND p.missionId <> $missionId " +
                                "WITH e, min(p.missionId) AS newOwner " +
                                "SET e.missionId = newOwner",
                        params);

                tx.run("MATCH (c:Conversation {id:'MAIN'}) WHERE $missionId IN c.lastMentionedIds " +
                                "WITH c, [x IN c.lastMentionedIds WHERE x <> $missionId] AS remaining " +
                                "SET c.lastMentionedIds = CASE WHEN size(remaining) = 0 THEN null ELSE remaining END",
                        params);

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

    public Optional<MissionResponse> find(String missionId) {
        try (var session = driver.session()) {
            var records = session.run("MATCH (m:Mission {id:$id}) RETURN m.status AS status, coalesce(m.environment, 'TEST') AS environment, m.progress AS progress, m.currentStep AS step, m.message AS message, m.updatedAt AS updatedAt, "
                            + "m.financialCriteriaMetric AS financialCriteriaMetric, m.financialCriteriaTargetAmount AS financialCriteriaTargetAmount, "
                            + "m.financialCriteriaCurrency AS financialCriteriaCurrency, m.financialCriteriaDeadline AS financialCriteriaDeadline",
                    Map.of("id", missionId)).list();
            return records.stream().findFirst().map(r -> new MissionResponse(
                    missionId,
                    MissionStatus.valueOf(r.get("status").asString()),
                    r.get("environment").asString(),
                    r.get("progress").asInt(),
                    r.get("step").asString(),
                    r.get("message").asString(),
                    Instant.parse(r.get("updatedAt").asString()),
                    mapFinancialCriteria(r)
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
                                    "m.message AS message, m.updatedAt AS updatedAt, " +
                                    "m.financialCriteriaMetric AS financialCriteriaMetric, " +
                                    "m.financialCriteriaTargetAmount AS financialCriteriaTargetAmount, " +
                                    "m.financialCriteriaCurrency AS financialCriteriaCurrency, " +
                                    "m.financialCriteriaDeadline AS financialCriteriaDeadline",
                            Map.of("ids", missionIds))
                    .list(r -> new MissionResponse(
                            r.get("id").asString(),
                            MissionStatus.valueOf(r.get("status").asString()),
                            r.get("environment").asString(),
                            r.get("progress").asInt(),
                            r.get("step").asString(),
                            r.get("message").asString(),
                            Instant.parse(r.get("updatedAt").asString()),
                            mapFinancialCriteria(r)
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
                                    "m.message AS message, m.updatedAt AS updatedAt, " +
                                    "m.financialCriteriaMetric AS financialCriteriaMetric, " +
                                    "m.financialCriteriaTargetAmount AS financialCriteriaTargetAmount, " +
                                    "m.financialCriteriaCurrency AS financialCriteriaCurrency, " +
                                    "m.financialCriteriaDeadline AS financialCriteriaDeadline " +
                                    "ORDER BY m.updatedAt DESC LIMIT $limit",
                            Map.of("limit", limit))
                    .list(r -> new MissionResponse(
                            r.get("id").asString(),
                            MissionStatus.valueOf(r.get("status").asString()),
                            r.get("environment").asString(),
                            r.get("progress").asInt(),
                            r.get("step").asString(),
                            r.get("message").asString(),
                            Instant.parse(r.get("updatedAt").asString()),
                            mapFinancialCriteria(r)
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

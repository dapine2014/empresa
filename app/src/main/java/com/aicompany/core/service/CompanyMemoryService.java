package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CompanyMemoryService {
    private final Driver driver;
    private final String defaultCeoModel;
    private final String defaultAgentModel;

    public CompanyMemoryService(
            Driver driver,
            @Value("${ollama.ceo-model}") String defaultCeoModel,
            @Value("${ollama.agent-model}") String defaultAgentModel) {

        this.driver = driver;
        this.defaultCeoModel = defaultCeoModel;
        this.defaultAgentModel = defaultAgentModel;
    }

    public void initialize() {
        int attempts = 0;
        while (attempts < 12) {
            try {
                initializeSchema();
                initializeCompanyAndAgents();
                return;
            } catch (Exception ex) {
                attempts++;
                if (attempts >= 12) {
                    throw ex;
                }
                try {
                    Thread.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrumpido esperando Neo4j", e);
                }
            }
        }
    }

    private void initializeSchema() {
        try (var session = driver.session()) {

            // Núcleo ya en uso por MissionExecutor/AgentRuntime.
            session.run("CREATE CONSTRAINT company_id IF NOT EXISTS FOR (c:Company) REQUIRE c.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT agent_id IF NOT EXISTS FOR (a:Agent) REQUIRE a.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT mission_id IF NOT EXISTS FOR (m:Mission) REQUIRE m.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT task_id IF NOT EXISTS FOR (t:AgentTask) REQUIRE t.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT opportunity_id IF NOT EXISTS FOR (o:Opportunity) REQUIRE o.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT evidence_id IF NOT EXISTS FOR (e:Evidence) REQUIRE e.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT team_id IF NOT EXISTS FOR (t:Team) REQUIRE t.id IS UNIQUE").consume();

            // Ampliación de memoria (EMPRESA_AI_TODO.md §21 / status.md §17):
            // solo el constraint de identidad — sin propiedades ni relaciones
            // definidas todavía, porque ningún flujo del código las escribe
            // o las lee aún. Es groundwork deliberado para features futuras
            // (organización autoevolutiva, mercado de predicción, cartera de
            // capital, gobernanza, gabinete multi-modelo, auto-mejora de
            // herramientas, mercado real, transparencia pública); cuando esa
            // lógica se implemente, definir ahí las propiedades y relaciones
            // reales según lo que la funcionalidad concreta necesite.
            session.run("CREATE CONSTRAINT role_id IF NOT EXISTS FOR (r:Role) REQUIRE r.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT role_version_id IF NOT EXISTS FOR (rv:RoleVersion) REQUIRE rv.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT agent_version_id IF NOT EXISTS FOR (av:AgentVersion) REQUIRE av.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT prediction_id IF NOT EXISTS FOR (p:Prediction) REQUIRE p.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT prediction_outcome_id IF NOT EXISTS FOR (po:PredictionOutcome) REQUIRE po.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT credibility_score_id IF NOT EXISTS FOR (cs:CredibilityScore) REQUIRE cs.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT capital_allocation_id IF NOT EXISTS FOR (ca:CapitalAllocation) REQUIRE ca.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT investment_id IF NOT EXISTS FOR (i:Investment) REQUIRE i.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT portfolio_id IF NOT EXISTS FOR (pf:Portfolio) REQUIRE pf.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT governance_rule_id IF NOT EXISTS FOR (gr:GovernanceRule) REQUIRE gr.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT governance_amendment_id IF NOT EXISTS FOR (ga:GovernanceAmendment) REQUIRE ga.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT model_id IF NOT EXISTS FOR (mo:Model) REQUIRE mo.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT model_assignment_id IF NOT EXISTS FOR (ma:ModelAssignment) REQUIRE ma.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT tool_id IF NOT EXISTS FOR (t2:Tool) REQUIRE t2.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT tool_version_id IF NOT EXISTS FOR (tv:ToolVersion) REQUIRE tv.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT public_decision_id IF NOT EXISTS FOR (pd:PublicDecision) REQUIRE pd.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT customer_id IF NOT EXISTS FOR (cu:Customer) REQUIRE cu.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT decision_id IF NOT EXISTS FOR (d:Decision) REQUIRE d.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT transaction_id IF NOT EXISTS FOR (tx:Transaction) REQUIRE tx.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT lesson_id IF NOT EXISTS FOR (l:Lesson) REQUIRE l.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT strategy_id IF NOT EXISTS FOR (s:Strategy) REQUIRE s.id IS UNIQUE").consume();

            // Memoria conversacional del Company Chat (ver CLAUDE.md
            // "Memoria conversacional"): índice en createdAt desde el día
            // uno -- la sesión de profiling de Neo4j que precedió a esta
            // feature encontró que latestTaskPerAgent() ordena en memoria
            // por no tener un índice así; no se repite ese patrón acá,
            // donde el volumen de mensajes crece sin límite natural.
            session.run("CREATE CONSTRAINT conversation_id IF NOT EXISTS FOR (c:Conversation) REQUIRE c.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT message_id IF NOT EXISTS FOR (msg:Message) REQUIRE msg.id IS UNIQUE").consume();
            session.run("CREATE INDEX message_created_at IF NOT EXISTS FOR (msg:Message) ON (msg.createdAt)").consume();
        }
    }

    private void initializeCompanyAndAgents() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Company {id:'AI-COMPANY'}) "
                        + "ON CREATE SET c.alertEmail='dapine@gmail.com', c.systemEmail='', c.mailPassword='' "
                        + "SET c.name='Forjai', c.status='ACTIVE', c.seedCapitalUsd=50.0, c.challengeDays=60");
                var agents = List.of(
                        new String[]{"ceo", "Alex", "Chief Executive Officer AI", "estratégico, crítico", defaultCeoModel},
                        new String[]{"sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados", defaultAgentModel},
                        new String[]{"product", "Luna", "Chief Product AI", "creativa, centrada en el usuario", defaultAgentModel},
                        new String[]{"finance", "Max", "Chief Finance AI", "analítico, conservador", defaultAgentModel},
                        new String[]{"engineering", "Neo", "Cloud Architect & Lead Backend", "pragmático, meticuloso", defaultAgentModel},
                        new String[]{"qa", "Vera", "QA & Cloud Performance Engineer", "escéptica, detallista", defaultAgentModel},
                        new String[]{"devops", "Diego", "Cloud Database & SRE / DevOps", "meticuloso, orientado a la estabilidad", defaultAgentModel},
                        new String[]{"backend", "Iris", "Dev Backend & Integrations", "riguroso, pragmático", defaultAgentModel},
                        new String[]{"frontend-ui", "Mila", "Frontend & Game UI Specialist", "creativa, atenta al detalle visual", defaultAgentModel},
                        new String[]{"interaction-design", "Kael", "Interactive Logic & Product Designer AI", "analítico, obsesionado con la experiencia de usuario", defaultAgentModel},
                        new String[]{"visual-design", "Maya", "Visual & Asset Director AI", "creativa, con ojo estético", defaultAgentModel},
                        new String[]{"telemetry", "Gael", "Telemetry & Analytics AI", "analítico, basado en datos", defaultAgentModel},
                        new String[]{"growth-content", "Kira", "Growth, Content & Community AI", "curiosa, comunicativa", defaultAgentModel},
                        new String[]{"community", "Nora", "Community Manager AI", "empática, cercana a la comunidad", defaultAgentModel}
                );
                for (var agent : agents) {
                    // ON CREATE, no SET incondicional de un valor fijo:
                    // a.status lo va actualizando AgentRuntime en cada
                    // transición real (WORKING/IDLE) -- un restart del
                    // proceso no debe pisarlo. La normalización final SÍ
                    // es incondicional pero idempotente sobre el valor ya
                    // real: preserva WORKING/IDLE tal cual si ya es uno
                    // de esos dos, y solo corrige cualquier otra cosa
                    // (p. ej. 'ACTIVE', el valor fijo que este seed
                    // escribía antes de este cambio, todavía presente en
                    // los 6 nodos reales de sesiones anteriores) a IDLE.
                    // a.model sigue el mismo criterio de "backfill sin
                    // pisar": coalesce preserva un valor ya seteado a
                    // mano (p. ej. vía PUT /agents/{id}/model), y solo
                    // lo completa la primera vez.
                    tx.run("MERGE (a:Agent {id:$id}) "
                                    + "ON CREATE SET a.status='IDLE' "
                                    + "SET a.name=$name, a.role=$role, a.personality=$personality, "
                                    + "a.status = CASE WHEN a.status IN ['WORKING','IDLE'] THEN a.status ELSE 'IDLE' END, "
                                    + "a.model = coalesce(a.model, $defaultModel) "
                                    + "REMOVE a.title",
                            Map.of("id", agent[0], "name", agent[1], "role", agent[2],
                                    "personality", agent[3], "defaultModel", agent[4]));
                }
                tx.run("MATCH (c:Company {id:'AI-COMPANY'}), (a:Agent) MERGE (a)-[:WORKS_FOR]->(c)");
                tx.run("MATCH (c:Company {id:'AI-COMPANY'}), (ceo:Agent {id:'ceo'}) MERGE (c)-[:HAS_CEO]->(ceo)");
                return null;
            });
        }
    }

    /**
     * Nombre real y persistente de un agente (nunca inventado por el LLM,
     * ver "Identidad persistente de agentes" en {@code CLAUDE.md}) — usado
     * para que {@code CeoService.chat} pueda decirle al modelo quién es de
     * verdad en vez de dejar que lo invente cuando se lo preguntan.
     */
    public Optional<String> agentName(String agentId) {
        try (var session = driver.session()) {
            return session.run("MATCH (a:Agent {id:$id}) RETURN a.name AS name", Map.of("id", agentId))
                    .list(record -> record.get("name").asString())
                    .stream().findFirst();
        }
    }

    /**
     * Roster real del equipo (sin el CEO) como texto plano — "buscó cuál
     * es su equipo real" no es distinto de "buscó su propio nombre":
     * mismo motivo que {@link #agentName}, para que {@code CeoService.chat}
     * pueda presentar al equipo real en vez de inventar roles genéricos
     * cuando alguien le pide "preséntame al equipo" en el chat.
     */
    public String teamRosterDescription() {
        try (var session = driver.session()) {
            var lines = session.run(
                    "MATCH (a:Agent) WHERE a.id <> 'ceo' RETURN a.name AS name, a.role AS role ORDER BY a.id")
                    .list(r -> "- " + r.get("name").asString() + " (" + r.get("role").asString() + ")");
            return String.join("\n", lines);
        }
    }

    /**
     * Modelo LLM real que este agente debe usar en su próxima llamada a
     * Ollama — {@code AgentRuntime}/{@code MissionExecutor}/
     * {@code ChatIntentRouter} lo resuelven antes de cada llamada, nunca
     * {@code CeoService} (que solo ejecuta la llamada que se le pide, sin
     * decidir qué modelo usar). {@code fallback} cubre el caso defensivo
     * de un agente sin backfill todavía (no debería pasar en la práctica:
     * {@link #initializeCompanyAndAgents()} lo completa para los 14
     * agentes conocidos al arrancar).
     */
    public String agentModel(String agentId, String fallback) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (a:Agent {id:$id}) RETURN coalesce(a.model, $fallback) AS model",
                            Map.of("id", agentId, "fallback", fallback))
                    .list(r -> r.get("model").asString())
                    .stream().findFirst()
                    .orElse(fallback);
        }
    }

    /**
     * Cambia el modelo real de un agente puntual (vía
     * {@code PUT /api/company/agents/{id}/model}) — toma efecto en la
     * próxima tarea/llamada de ese agente, sin caché ni reinicio.
     */
    public void setAgentModel(String agentId, String model) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                var result = tx.run("MATCH (a:Agent {id:$id}) SET a.model=$model",
                        Map.of("id", agentId, "model", model));

                if (result.consume().counters().propertiesSet() == 0) {
                    throw new IllegalArgumentException("No existe el agente " + agentId);
                }

                return null;
            });
        }
    }

    /**
     * Correo al que se envían las alertas inmediatas (`empresa.md` §18) —
     * seed inicial `dapine@gmail.com` (`ON CREATE` en
     * {@link #initializeCompanyAndAgents}, nunca se pisa en arranques
     * siguientes), editable desde el Command Center web vía
     * {@code PUT /api/company/settings}.
     */
    public String alertEmail() {
        try (var session = driver.session()) {
            return session.run(
                    "MATCH (c:Company {id:'AI-COMPANY'}) RETURN coalesce(c.alertEmail, 'dapine@gmail.com') AS email")
                    .single().get("email").asString();
        }
    }

    public void setAlertEmail(String email) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (c:Company {id:'AI-COMPANY'}) SET c.alertEmail=$email", Map.of("email", email));
                return null;
            });
        }
    }

    /**
     * Correo propio del sistema (remitente/usuario SMTP de las alertas) —
     * distinto de {@link #alertEmail}, que es a quién le llegan. Vacío por
     * default (`ON CREATE` en {@link #initializeCompanyAndAgents}): sin
     * configurarlo desde el Command Center web, {@link AlertMailService}
     * no intenta enviar nada.
     */
    public String systemEmail() {
        try (var session = driver.session()) {
            return session.run(
                    "MATCH (c:Company {id:'AI-COMPANY'}) RETURN coalesce(c.systemEmail, '') AS email")
                    .single().get("email").asString();
        }
    }

    public void setSystemEmail(String email) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (c:Company {id:'AI-COMPANY'}) SET c.systemEmail=$email", Map.of("email", email));
                return null;
            });
        }
    }

    /**
     * App Password SMTP del correo propio del sistema. Se guarda en texto
     * plano en esta misma instancia de Neo4j (compartida con otros
     * proyectos en esta máquina, ver "Memoria: Neo4j" en `CLAUDE.md`) —
     * decisión acordada con el usuario para el alcance de MVP (un solo
     * operador, máquina de desarrollo propia); nunca se devuelve por API
     * (`SettingsResponse` no la incluye).
     */
    public String mailPassword() {
        try (var session = driver.session()) {
            return session.run(
                    "MATCH (c:Company {id:'AI-COMPANY'}) RETURN coalesce(c.mailPassword, '') AS password")
                    .single().get("password").asString();
        }
    }

    public void setMailPassword(String password) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (c:Company {id:'AI-COMPANY'}) SET c.mailPassword=$password", Map.of("password", password));
                return null;
            });
        }
    }

    public List<Map<String, Object>> agents() {
        try (var session = driver.session()) {
            return session.run("MATCH (a:Agent) RETURN a.id AS id, a.name AS name, a.role AS role, a.personality AS personality ORDER BY a.id")
                    .list(record -> Map.of(
                            "id", record.get("id").asString(),
                            "name", record.get("name").asString(),
                            "role", record.get("role").asString(),
                            "personality", record.get("personality").asString()));
        }
    }
}

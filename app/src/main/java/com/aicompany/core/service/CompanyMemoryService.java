package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CompanyMemoryService {
    private final Driver driver;

    public CompanyMemoryService(Driver driver) {
        this.driver = driver;
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
        }
    }

    private void initializeCompanyAndAgents() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Company {id:'AI-COMPANY'}) SET c.name='AI Company', c.status='ACTIVE', c.seedCapitalUsd=50.0, c.challengeDays=60");
                var agents = List.of(
                        new String[]{"ceo", "Alex", "Chief Executive Officer AI", "estratégico, crítico"},
                        new String[]{"sales", "Sofia", "Director of Sales AI", "persuasiva, orientada a resultados"},
                        new String[]{"product", "Luna", "Chief Product AI", "creativa, centrada en el usuario"},
                        new String[]{"finance", "Max", "Chief Finance AI", "analítico, conservador"},
                        new String[]{"engineering", "Neo", "Chief Engineering AI", "pragmático, meticuloso"},
                        new String[]{"qa", "Vera", "QA & Operations AI", "escéptica, detallista"}
                );
                for (var agent : agents) {
                    tx.run("MERGE (a:Agent {id:$id}) SET a.name=$name, a.role=$role, a.personality=$personality, a.status='ACTIVE' REMOVE a.title",
                            Map.of("id", agent[0], "name", agent[1], "role", agent[2], "personality", agent[3]));
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

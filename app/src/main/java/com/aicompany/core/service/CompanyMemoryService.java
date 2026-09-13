package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
            session.run("CREATE CONSTRAINT company_id IF NOT EXISTS FOR (c:Company) REQUIRE c.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT agent_id IF NOT EXISTS FOR (a:Agent) REQUIRE a.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT mission_id IF NOT EXISTS FOR (m:Mission) REQUIRE m.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT task_id IF NOT EXISTS FOR (t:AgentTask) REQUIRE t.id IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT opportunity_id IF NOT EXISTS FOR (o:Opportunity) REQUIRE o.id IS UNIQUE").consume();
        }
    }

    private void initializeCompanyAndAgents() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Company {id:'AI-COMPANY'}) SET c.name='AI Company', c.status='ACTIVE', c.seedCapitalUsd=50.0, c.challengeDays=60");
                var agents = List.of(
                        new String[]{"ceo", "CEO", "Chief Executive Officer AI"},
                        new String[]{"sales", "Sales", "Director of Sales AI"},
                        new String[]{"product", "Product", "Chief Product AI"},
                        new String[]{"finance", "Finance", "Chief Finance AI"},
                        new String[]{"engineering", "Engineering", "Chief Engineering AI"},
                        new String[]{"qa", "QA & Operations", "QA & Operations AI"}
                );
                for (var agent : agents) {
                    tx.run("MERGE (a:Agent {id:$id}) SET a.name=$name, a.title=$title, a.status='ACTIVE'", Map.of("id", agent[0], "name", agent[1], "title", agent[2]));
                }
                tx.run("MATCH (c:Company {id:'AI-COMPANY'}), (a:Agent) MERGE (a)-[:WORKS_FOR]->(c)");
                tx.run("MATCH (c:Company {id:'AI-COMPANY'}), (ceo:Agent {id:'ceo'}) MERGE (c)-[:HAS_CEO]->(ceo)");
                return null;
            });
        }
    }

    public List<Map<String, Object>> agents() {
        try (var session = driver.session()) {
            return session.run("MATCH (a:Agent) RETURN a.id AS id, a.name AS name, a.title AS title ORDER BY a.id")
                    .list(record -> Map.of(
                            "id", record.get("id").asString(),
                            "name", record.get("name").asString(),
                            "title", record.get("title").asString()));
        }
    }
}

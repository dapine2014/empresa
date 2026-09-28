package com.aicompany.core.service;

import com.aicompany.core.model.FinanceCorrectionCommand;
import com.aicompany.core.model.FinanceCustomer;
import com.aicompany.core.model.FinanceExpenseCommand;
import com.aicompany.core.model.FinanceMovement;
import com.aicompany.core.model.FinanceSaleCommand;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Neo4j de finanzas (spec 2026-09-27): Customer y Transaction existentes (ahora con misión opcional), Expense y
 * Correction nuevos. Cada registro lleva su Evidence "declarada por el fundador". Nada se actualiza ni se borra.
 */
@Service
public class FinanceMemoryService {

    /** Entorno de una misión con la regla de misiones viejas: sin campo = TEST, salvo MISSION-001. */
    private static final String MISSION_ENV = "CASE WHEN m.environment IS NOT NULL THEN m.environment "
            + "WHEN m.id = 'MISSION-001' THEN 'PRODUCTION' ELSE 'TEST' END";

    private final Driver driver;

    public FinanceMemoryService(Driver driver) {
        this.driver = driver;
    }

    public Optional<String> missionEnvironment(String missionId) {
        return single("MATCH (m:Mission {id:$id}) RETURN " + MISSION_ENV + " AS v", Map.of("id", missionId));
    }

    public Optional<String> customerKind(String customerId) {
        return single("MATCH (c:Customer {id:$id}) RETURN CASE WHEN c.status = 'LEAD' THEN 'LEAD' ELSE 'CUSTOMER' END AS v",
                Map.of("id", customerId == null ? "" : customerId));
    }

    public Optional<String> movementKind(String id) {
        return single("OPTIONAL MATCH (t:Transaction {id:$id}) OPTIONAL MATCH (e:Expense {id:$id}) "
                + "OPTIONAL MATCH (c:Correction {id:$id}) RETURN CASE WHEN t IS NOT NULL THEN 'SALE' "
                + "WHEN e IS NOT NULL THEN 'EXPENSE' WHEN c IS NOT NULL THEN 'CORRECTION' END AS v",
                Map.of("id", id == null ? "" : id));
    }

    public void createCustomer(String id, String name, String contact, String missionId, String evidenceDescription,
                               String evidenceLink) {
        write(tx -> {
            var params = params("id", id, "name", name, "contact", contact, "missionId", missionId);
            tx.run("CREATE (c:Customer {id:$id, name:$name, contact:$contact, recordedAt:$now})", params);
            linkMission(tx, "Customer", "HAS_CUSTOMER", id, missionId);
            evidence(tx, "Customer", id, evidenceDescription, evidenceLink);
        });
    }

    public void createSale(String id, FinanceSaleCommand c, String environment) {
        write(tx -> {
            var params = params("id", id, "customerId", c.customerId(), "description", c.description().strip(),
                    "revenueUsd", c.revenueUsd(), "costUsd", c.costUsd(), "netProfitUsd", c.revenueUsd() - c.costUsd(),
                    "environment", environment, "missionId", blankToNull(c.missionId()));
            tx.run("MATCH (cu:Customer {id:$customerId}) CREATE (t:Transaction {id:$id, customerId:$customerId, "
                    + "description:$description, revenueUsd:$revenueUsd, costUsd:$costUsd, netProfitUsd:$netProfitUsd, "
                    + "environment:$environment, missionId:$missionId, recordedAt:$now})-[:FOR_CUSTOMER]->(cu)", params);
            linkMission(tx, "Transaction", "HAS_TRANSACTION", id, blankToNull(c.missionId()));
            evidence(tx, "Transaction", id, c.evidenceDescription(), c.evidenceLink());
        });
    }

    public void createExpense(String id, FinanceExpenseCommand c, String environment) {
        write(tx -> {
            tx.run("CREATE (e:Expense {id:$id, description:$description, amountUsd:$amountUsd, environment:$environment, "
                            + "missionId:$missionId, recordedAt:$now})",
                    params("id", id, "description", c.description().strip(), "amountUsd", c.amountUsd(),
                            "environment", environment, "missionId", blankToNull(c.missionId())));
            linkMission(tx, "Expense", "HAS_EXPENSE", id, blankToNull(c.missionId()));
            evidence(tx, "Expense", id, c.evidenceDescription(), c.evidenceLink());
        });
    }

    public void createCorrection(String id, FinanceCorrectionCommand c) {
        write(tx -> {
            tx.run("MATCH (target {id:$targetId}) WHERE target:Transaction OR target:Expense "
                            + "CREATE (c:Correction {id:$id, targetId:$targetId, revenueAdjustmentUsd:$revenue, "
                            + "costAdjustmentUsd:$cost, reason:$reason, recordedAt:$now})-[:CORRECTS]->(target)",
                    params("id", id, "targetId", c.targetId(), "revenue", c.revenueAdjustmentUsd(),
                            "cost", c.costAdjustmentUsd(), "reason", c.reason().strip()));
            evidence(tx, "Correction", id, c.evidenceDescription(), c.evidenceLink());
        });
    }

    public List<FinanceMovement> movements() {
        var out = new ArrayList<FinanceMovement>();
        try (var session = driver.session()) {
            session.run("MATCH (t:Transaction) OPTIONAL MATCH (m:Mission)-[:HAS_TRANSACTION]->(t) "
                            + "OPTIONAL MATCH (t)-[:FOR_CUSTOMER]->(cu:Customer) "
                            + "RETURN t.id AS id, t.description AS d, coalesce(t.revenueUsd, 0.0) AS r, "
                            + "coalesce(t.costUsd, 0.0) AS c, coalesce(t.missionId, m.id) AS mission, "
                            + "coalesce(t.environment, CASE WHEN m IS NULL THEN 'PRODUCTION' ELSE " + MISSION_ENV + " END) AS env, "
                            + "t.recordedAt AS at, cu.name AS who")
                    .list().forEach(r -> out.add(movement(r, "SALE", r.get("r").asDouble(), r.get("c").asDouble(), null)));
            session.run("MATCH (e:Expense) RETURN e.id AS id, e.description AS d, e.amountUsd AS c, e.missionId AS mission, "
                            + "e.environment AS env, e.recordedAt AS at, null AS who")
                    .list().forEach(r -> out.add(movement(r, "EXPENSE", 0, r.get("c").asDouble(), null)));
            session.run("MATCH (c:Correction)-[:CORRECTS]->(target) OPTIONAL MATCH (m:Mission)-[:HAS_TRANSACTION]->(target) "
                            + "RETURN c.id AS id, c.reason AS d, c.revenueAdjustmentUsd AS r, c.costAdjustmentUsd AS c, "
                            + "coalesce(target.missionId, m.id) AS mission, coalesce(target.environment, "
                            + "CASE WHEN m IS NULL THEN 'PRODUCTION' ELSE " + MISSION_ENV + " END) AS env, "
                            + "c.recordedAt AS at, null AS who, c.targetId AS target")
                    .list().forEach(r -> out.add(movement(r, "CORRECTION", r.get("r").asDouble(), r.get("c").asDouble(),
                            r.get("target").asString(null))));
        }
        return out;
    }

    public List<FinanceCustomer> customers() {
        try (var session = driver.session()) {
            return session.run("MATCH (c:Customer) WHERE c.status IS NULL OPTIONAL MATCH (m:Mission)-[:HAS_CUSTOMER]->(c) "
                            + "RETURN c.id AS id, c.name AS name, c.contact AS contact, m.id AS mission, "
                            + "c.recordedAt AS at ORDER BY c.name")
                    .list(r -> new FinanceCustomer(r.get("id").asString(), r.get("name").asString(null),
                            r.get("contact").asString(null), r.get("mission").asString(null),
                            instant(r.get("at").asString(null))));
        }
    }

    private static FinanceMovement movement(Record r, String kind, double revenue, double cost, String target) {
        return new FinanceMovement(r.get("id").asString(), kind, r.get("d").asString(""), revenue, cost,
                r.get("mission").asString(null), r.get("env").asString("PRODUCTION"), instant(r.get("at").asString(null)),
                target, r.get("who").asString(null));
    }

    private static Instant instant(String text) {
        try {
            return text == null ? Instant.EPOCH : Instant.parse(text);
        } catch (Exception ex) {
            return Instant.EPOCH;
        }
    }

    private void linkMission(TransactionContext tx, String label, String rel, String id, String missionId) {
        if (missionId != null) {
            tx.run("MATCH (m:Mission {id:$missionId}), (x:" + label + " {id:$id}) MERGE (m)-[:" + rel + "]->(x)",
                    Map.of("missionId", missionId, "id", id));
        }
    }

    private void evidence(TransactionContext tx, String label, String id, String description, String link) {
        tx.run("MATCH (x:" + label + " {id:$id}) CREATE (ev:Evidence {id: $id + '-EVIDENCE', description:$description, "
                        + "source:$source, sourceType:'FOUNDER_DECLARED', verified:false, agentId:'human', createdAt:$now}) "
                        + "CREATE (x)-[:HAS_EVIDENCE]->(ev)",
                params("id", id, "description", description.strip(), "source", blankToNull(link)));
    }

    private Optional<String> single(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            return session.run(cypher, params).list(r -> r.get("v").isNull() ? null : r.get("v").asString()).stream()
                    .filter(java.util.Objects::nonNull).findFirst();
        }
    }

    private void write(java.util.function.Consumer<TransactionContext> work) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                work.accept(tx);
                return null;
            });
        }
    }

    private static Map<String, Object> params(Object... keyValues) {
        var map = new HashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        map.put("now", Instant.now().toString());
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

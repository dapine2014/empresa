package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Persistencia de "Customer Validation": clientes y transacciones reales,
 * distintos de lo que un agente pueda afirmar en un {@code AgentResult}.
 * Cada registro exige su propia evidencia (ver {@code CustomerService}),
 * igual que la evidencia de tareas de agente en {@link MissionMemoryService}.
 */
@Service
public class CustomerMemoryService {

    private final Driver driver;

    public CustomerMemoryService(Driver driver) {
        this.driver = driver;
    }

    public boolean missionExists(String missionId) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (m:Mission {id:$missionId}) RETURN count(m) AS total",
                            Map.of("missionId", missionId))
                    .single()
                    .get("total")
                    .asLong() > 0;
        }
    }

    public void registerCustomer(
            String missionId,
            String customerId,
            String name,
            String contact,
            AgentResult.Evidence evidence) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var recordedAt = Instant.now().toString();

                tx.run("MATCH (m:Mission {id:$missionId}) " +
                                "MERGE (c:Customer {id:$customerId}) " +
                                "SET c.missionId=$missionId, c.name=$name, " +
                                "c.contact=$contact, c.recordedAt=$recordedAt " +
                                "MERGE (m)-[:HAS_CUSTOMER]->(c)",
                        Map.of(
                                "missionId", missionId,
                                "customerId", customerId,
                                "name", name,
                                "contact", contact == null ? "" : contact,
                                "recordedAt", recordedAt
                        ));

                tx.run("MATCH (c:Customer {id:$customerId}) " +
                                "MERGE (e:Evidence {id:$evidenceId}) " +
                                "SET e.missionId=$missionId, e.agentId='human', " +
                                "e.description=$description, e.source=$source, " +
                                "e.sourceType=$sourceType, e.verified=$verified, " +
                                "e.updatedAt=$updatedAt " +
                                "MERGE (c)-[:HAS_EVIDENCE]->(e)",
                        Map.of(
                                "customerId", customerId,
                                "evidenceId", customerId + "-EVIDENCE",
                                "missionId", missionId,
                                "description", evidence.description() == null ? "" : evidence.description(),
                                "source", evidence.source() == null ? "" : evidence.source(),
                                "sourceType", evidence.sourceType() == null ? "" : evidence.sourceType(),
                                "verified", evidence.verified(),
                                "updatedAt", recordedAt
                        ));

                return null;
            });
        }
    }

    public Optional<String> registerTransaction(
            String missionId,
            String customerId,
            String transactionId,
            String description,
            double revenueUsd,
            double costUsd,
            AgentResult.Evidence evidence) {

        try (var session = driver.session()) {
            return session.executeWrite(tx -> {

                var customerExists = tx.run(
                                "MATCH (c:Customer {id:$customerId, missionId:$missionId}) " +
                                        "RETURN count(c) AS total",
                                Map.of("customerId", customerId, "missionId", missionId))
                        .single()
                        .get("total")
                        .asLong() > 0;

                if (!customerExists) {
                    return Optional.<String>empty();
                }

                var recordedAt = Instant.now().toString();
                var netProfitUsd = revenueUsd - costUsd;

                tx.run("MATCH (m:Mission {id:$missionId}), (c:Customer {id:$customerId}) " +
                                "MERGE (t:Transaction {id:$transactionId}) " +
                                "SET t.missionId=$missionId, t.customerId=$customerId, " +
                                "t.description=$description, t.revenueUsd=$revenueUsd, " +
                                "t.costUsd=$costUsd, t.netProfitUsd=$netProfitUsd, " +
                                "t.recordedAt=$recordedAt " +
                                "MERGE (m)-[:HAS_TRANSACTION]->(t) " +
                                "MERGE (t)-[:FOR_CUSTOMER]->(c)",
                        Map.of(
                                "missionId", missionId,
                                "customerId", customerId,
                                "transactionId", transactionId,
                                "description", description,
                                "revenueUsd", revenueUsd,
                                "costUsd", costUsd,
                                "netProfitUsd", netProfitUsd,
                                "recordedAt", recordedAt
                        ));

                tx.run("MATCH (t:Transaction {id:$transactionId}) " +
                                "MERGE (e:Evidence {id:$evidenceId}) " +
                                "SET e.missionId=$missionId, e.agentId='human', " +
                                "e.description=$description, e.source=$source, " +
                                "e.sourceType=$sourceType, e.verified=$verified, " +
                                "e.updatedAt=$updatedAt " +
                                "MERGE (t)-[:HAS_EVIDENCE]->(e)",
                        Map.of(
                                "transactionId", transactionId,
                                "evidenceId", transactionId + "-EVIDENCE",
                                "missionId", missionId,
                                "description", evidence.description() == null ? "" : evidence.description(),
                                "source", evidence.source() == null ? "" : evidence.source(),
                                "sourceType", evidence.sourceType() == null ? "" : evidence.sourceType(),
                                "verified", evidence.verified(),
                                "updatedAt", recordedAt
                        ));

                return Optional.of(recordedAt);
            });
        }
    }

    public double[] totalRevenueAndCost(String missionId) {
        try (var session = driver.session()) {
            var record = session.run(
                    "MATCH (m:Mission {id:$missionId}) " +
                            "OPTIONAL MATCH (m)-[:HAS_TRANSACTION]->(t:Transaction) " +
                            "RETURN coalesce(sum(t.revenueUsd), 0.0) AS totalRevenue, " +
                            "coalesce(sum(t.costUsd), 0.0) AS totalCost",
                    Map.of("missionId", missionId)
            ).single();

            return new double[]{
                    record.get("totalRevenue").asDouble(),
                    record.get("totalCost").asDouble()
            };
        }
    }
}

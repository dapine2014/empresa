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
            String leadId,
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

                // Si la misión ya generó su Opportunity (MissionExecutor la
                // crea al consolidar, ver OpportunityMemoryService), este
                // cliente real también queda colgado de ella -- no solo de
                // Mission directamente -- para que el grafo siga el modelo
                // objetivo Mission->Opportunity->Customer. Si todavía no
                // existe (p. ej. la misión no ha llegado a consolidación),
                // el MATCH simplemente no encuentra nada y no pasa nada: no
                // se crea una Opportunity vacía como efecto secundario de
                // registrar un cliente.
                tx.run("MATCH (o:Opportunity {id:$opportunityId}), (c:Customer {id:$customerId}) " +
                                "MERGE (o)-[:HAS_CUSTOMER]->(c)",
                        Map.of(
                                "opportunityId", missionId + "-OPPORTUNITY",
                                "customerId", customerId
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

                // Solo si el cliente real viene de un LEAD que ya se marcó
                // CONVERTIDO (CustomerService llama a
                // OpportunityMemoryService.markConverted antes que esto) --
                // enlaza el cliente real nuevo al LEAD del que salió, sin
                // fusionar los nodos ni tocar el LEAD más allá de su
                // status (ver spec, decisión 2).
                if (leadId != null && !leadId.isBlank()) {

                    tx.run("MATCH (c:Customer {id:$customerId}), (lead:Customer {id:$leadId}) " +
                                    "MERGE (c)-[:CONVERTED_FROM]->(lead)",
                            Map.of(
                                    "customerId", customerId,
                                    "leadId", leadId
                            ));
                }

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

    /**
     * Igual que {@link #totalRevenueAndCost} pero sin filtrar por misión —
     * el "gasto total de la compañía" que responde el Chat Intent Router
     * (intent "cuánto hemos gastado/ganado") sobre todas las transacciones
     * reales registradas, de cualquier misión.
     */
    /**
     * {@code {clientes reales, prospectos}} — un cliente real
     * ({@link #registerCustomer}, canal humano) nunca tiene {@code status}
     * seteado; un prospecto/LEAD ({@code OpportunityMemoryService.recordCandidate})
     * siempre tiene {@code status='LEAD'}. Usado por el status agregado de
     * la empresa ({@code QueryIntent.COMPANY_STATUS} en
     * {@code ChatIntentRouter}) para no confundir un candidato de agente
     * (hipótesis) con una relación real confirmada.
     */
    public long[] countCustomersAndProspects() {
        try (var session = driver.session()) {
            var record = session.run(
                    "MATCH (c:Customer) "
                            + "RETURN count(CASE WHEN c.status IS NULL THEN 1 END) AS customers, "
                            + "count(CASE WHEN c.status='LEAD' THEN 1 END) AS prospects"
            ).single();

            return new long[]{
                    record.get("customers").asLong(),
                    record.get("prospects").asLong()
            };
        }
    }

    public double[] companyWideTotalRevenueAndCost() {
        try (var session = driver.session()) {
            var record = session.run(
                    "MATCH (t:Transaction) " +
                            "RETURN coalesce(sum(t.revenueUsd), 0.0) AS totalRevenue, " +
                            "coalesce(sum(t.costUsd), 0.0) AS totalCost"
            ).single();

            return new double[]{
                    record.get("totalRevenue").asDouble(),
                    record.get("totalCost").asDouble()
            };
        }
    }
}

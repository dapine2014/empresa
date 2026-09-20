package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.CustomerCommand;
import com.aicompany.core.model.CustomerResponse;
import com.aicompany.core.model.MissionProfitResponse;
import com.aicompany.core.model.TransactionCommand;
import com.aicompany.core.model.TransactionResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Customer Validation: registrar clientes y ventas reales, distinto de lo
 * que cualquier agente pueda afirmar en un {@code AgentResult}. Ni un
 * cliente ni una venta se aceptan sin evidencia — misma regla de "el
 * agente afirma X ≠ la empresa puede demostrar X" aplicada aquí a quien
 * sea que registre el dato (hoy: el fundador humano; no hay todavía un
 * flujo que permita a un agente llamar este endpoint autónomamente).
 */
@Service
public class CustomerService {

    private static final double GOOD_THRESHOLD = 50.0;
    private static final double VERY_GOOD_THRESHOLD = 100.0;
    private static final double EXCELLENT_THRESHOLD = 1000.0;
    private static final double EXTRAORDINARY_THRESHOLD = 5000.0;

    private final CustomerMemoryService memory;
    private final EvidenceValidationGate evidenceGate;
    private final AppProperties appProperties;
    private final OpportunityMemoryService opportunityMemory;

    public CustomerService(
            CustomerMemoryService memory,
            EvidenceValidationGate evidenceGate,
            AppProperties appProperties,
            OpportunityMemoryService opportunityMemory) {

        this.memory = memory;
        this.evidenceGate = evidenceGate;
        this.appProperties = appProperties;
        this.opportunityMemory = opportunityMemory;
    }

    public CustomerResponse registerCustomer(
            String missionId,
            CustomerCommand command) {

        if (!memory.missionExists(missionId)) {

            throw new IllegalArgumentException(
                    "No existe la misión " + missionId
            );
        }

        var evidence = new AgentResult.Evidence(
                command.evidenceDescription(),
                command.evidenceSource(),
                command.evidenceSourceType(),
                command.evidenceVerified()
        );

        var validation =
                evidenceGate.validate(List.of(evidence));

        if (!validation.valid()) {

            throw new IllegalStateException(
                    "Evidencia inválida: "
                            + String.join("; ", validation.errors())
            );
        }

        if (command.leadId() != null && !command.leadId().isBlank()) {

            if (command.customerId().equals(command.leadId())) {

                throw new IllegalArgumentException(
                        "El cliente real debe ser un nodo nuevo, distinto del lead "
                                + command.leadId()
                );
            }

            var converted = opportunityMemory.markConverted(command.leadId());

            if (!converted) {

                throw new IllegalStateException(
                        "El lead " + command.leadId()
                                + " no existe o ya no está en estado LEAD"
                );
            }
        }

        memory.registerCustomer(
                missionId,
                command.customerId(),
                command.name(),
                command.contact(),
                command.leadId(),
                evidence
        );

        return new CustomerResponse(
                command.customerId(),
                missionId,
                command.name(),
                Instant.now()
        );
    }

    public Optional<TransactionResponse> registerTransaction(
            String missionId,
            TransactionCommand command) {

        var evidence = new AgentResult.Evidence(
                command.evidenceDescription(),
                command.evidenceSource(),
                command.evidenceSourceType(),
                command.evidenceVerified()
        );

        var validation =
                evidenceGate.validate(List.of(evidence));

        if (!validation.valid()) {

            throw new IllegalStateException(
                    "Evidencia inválida: "
                            + String.join("; ", validation.errors())
            );
        }

        var recordedAt =
                memory.registerTransaction(
                        missionId,
                        command.customerId(),
                        command.transactionId(),
                        command.description(),
                        command.revenueUsd(),
                        command.costUsd(),
                        evidence
                );

        return recordedAt.map(timestamp -> new TransactionResponse(
                command.transactionId(),
                missionId,
                command.customerId(),
                command.revenueUsd(),
                command.costUsd(),
                command.revenueUsd() - command.costUsd(),
                Instant.parse(timestamp)
        ));
    }

    public MissionProfitResponse netProfit(String missionId) {

        var totals = memory.totalRevenueAndCost(missionId);
        var totalRevenue = totals[0];
        var totalCost = totals[1];
        var netProfit = totalRevenue - totalCost;
        var seedCapitalUsd = appProperties.seedCapitalUsd();

        var successCriterionMet = netProfit > seedCapitalUsd;

        return new MissionProfitResponse(
                missionId,
                totalRevenue,
                totalCost,
                netProfit,
                seedCapitalUsd,
                successCriterionMet,
                successLevel(netProfit)
        );
    }

    private String successLevel(double netProfit) {

        if (netProfit >= EXTRAORDINARY_THRESHOLD) {
            return "EXTRAORDINARIO";
        }

        if (netProfit >= EXCELLENT_THRESHOLD) {
            return "EXCELENTE";
        }

        if (netProfit > VERY_GOOD_THRESHOLD) {
            return "MUY_BUENO";
        }

        if (netProfit > GOOD_THRESHOLD) {
            return "BUENO";
        }

        return "NINGUNO";
    }
}

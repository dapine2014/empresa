package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.model.CustomerCommand;
import com.aicompany.core.model.CustomerResponse;
import com.aicompany.core.model.FinancialCriteriaEvaluation;
import com.aicompany.core.model.FinancialCriteriaResponse;
import com.aicompany.core.model.MissionProfitResponse;
import com.aicompany.core.model.PolicyKey;
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

    private final CustomerMemoryService memory;
    private final EvidenceValidationGate evidenceGate;
    private final CompanyPolicyService companyPolicyService;
    private final MissionMemoryService missionMemory;

    public CustomerService(
            CustomerMemoryService memory,
            EvidenceValidationGate evidenceGate,
            CompanyPolicyService companyPolicyService,
            MissionMemoryService missionMemory) {

        this.memory = memory;
        this.evidenceGate = evidenceGate;
        this.companyPolicyService = companyPolicyService;
        this.missionMemory = missionMemory;
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

        memory.registerCustomer(
                missionId,
                command.customerId(),
                command.name(),
                command.contact(),
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
        var seedCapitalUsd = companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD);

        var successCriterionMet = netProfit > seedCapitalUsd;

        var evaluation = missionMemory.financialCriteria(missionId)
                .map(fc -> evaluate(fc, netProfit))
                .orElse(null);

        return new MissionProfitResponse(
                missionId, totalRevenue, totalCost, netProfit, seedCapitalUsd,
                successCriterionMet, successLevel(netProfit), evaluation
        );
    }

    private FinancialCriteriaEvaluation evaluate(FinancialCriteriaResponse criteria, double netProfit) {

        var criterionMet = netProfit >= criteria.targetAmount();
        var progressPct = criteria.targetAmount() > 0 ? (netProfit / criteria.targetAmount()) * 100 : 0;
        var deadlinePassed = criteria.deadline() == null
                ? null
                : (Boolean) java.time.LocalDate.now().isAfter(criteria.deadline());

        return new FinancialCriteriaEvaluation(
                criteria.metric(), criteria.targetAmount(), criteria.currency(), criteria.deadline(),
                criterionMet, progressPct, deadlinePassed
        );
    }

    private String successLevel(double netProfit) {

        if (netProfit >= companyPolicyService.activeValue(PolicyKey.SUCCESS_THRESHOLD_EXTRAORDINARY)) {
            return "EXTRAORDINARIO";
        }

        if (netProfit >= companyPolicyService.activeValue(PolicyKey.SUCCESS_THRESHOLD_EXCELLENT)) {
            return "EXCELENTE";
        }

        if (netProfit > companyPolicyService.activeValue(PolicyKey.SUCCESS_THRESHOLD_VERY_GOOD)) {
            return "MUY_BUENO";
        }

        if (netProfit > companyPolicyService.activeValue(PolicyKey.SUCCESS_THRESHOLD_GOOD)) {
            return "BUENO";
        }

        return "NINGUNO";
    }
}

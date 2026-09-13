package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.AgentResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResultValidatorTest {

    private final AgentResultValidator validator = new AgentResultValidator();

    @Test
    void acceptsNotValidatedResultWithConsistentData() {
        var result = result(
                "NOT_VALIDATED",
                List.of(),
                List.of(new AgentResult.Calculation("margen", 100, 30, "SUBTRACT", 70))
        );

        var validation = validator.validate(result);

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void acceptsValidatedResultOnlyWithVerifiableEvidence() {
        var evidence = new AgentResult.Evidence(
                "Pedido aceptado",
                "https://example.com/pedido/123",
                "ORDER",
                true
        );

        var validation = validator.validate(result("VALIDATED", List.of(evidence), List.of()));

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void rejectsValidatedResultWithoutVerifiedEvidence() {
        var validation = validator.validate(result("VALIDATED", List.of(), List.of()));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
                .anyMatch(error -> error.contains("VALIDATED requiere")));
    }

    @Test
    void rejectsInconsistentCalculation() {
        var calculation = new AgentResult.Calculation("margen", 100, 30, "SUBTRACT", 80);

        var validation = validator.validate(result("NOT_VALIDATED", List.of(), List.of(calculation)));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
                .anyMatch(error -> error.contains("Cálculo inconsistente")));
    }

    @Test
    void rejectsVerifiedEvidenceWithoutSource() {
        var evidence = new AgentResult.Evidence("Pedido aceptado", "", "ORDER", true);

        var validation = validator.validate(result("PARTIALLY_VALIDATED", List.of(evidence), List.of()));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
                .anyMatch(error -> error.contains("evidencia verificada debe tener source")));
    }

    private AgentResult result(
            String verificationStatus,
            List<AgentResult.Evidence> evidence,
            List<AgentResult.Calculation> calculations) {

        return new AgentResult(
                "finance",
                "UNIT_ECONOMICS",
                verificationStatus,
                List.of(),
                List.of("Se debe validar la demanda."),
                List.of(),
                evidence,
                List.of(),
                calculations,
                List.of(),
                "Validar antes de invertir.",
                0.7
        );
    }
}

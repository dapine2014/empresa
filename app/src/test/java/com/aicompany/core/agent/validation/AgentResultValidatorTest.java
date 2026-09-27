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
    void acceptsMultiplyAndDivideRecalculatedInJava() {
        // Verificado en vivo (PROVIDERS-VERIFY-DISC): Max y Luna necesitan márgenes en % y punto de equilibrio.
        var validation = validator.validate(result("NOT_VALIDATED", List.of(), List.of(
                new AgentResult.Calculation("ingreso mensual", 12, 25, "MULTIPLY", 300),
                new AgentResult.Calculation("margen unitario", 19, 20, "DIVIDE", 0.95),
                new AgentResult.Calculation("margen %", 0.95, 100, "multiply", 95))));

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void acceptsDivisionRoundedToTwoDecimals() {
        var calculation = new AgentResult.Calculation("unidades por dólar", 1, 3, "DIVIDE", 0.33);

        var validation = validator.validate(result("NOT_VALIDATED", List.of(), List.of(calculation)));

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void rejectsInconsistentDivision() {
        // El caso real: "margen 95%" declarado sobre una operación que da otro número.
        var calculation = new AgentResult.Calculation("margen %", 19, 20, "DIVIDE", 95);

        var validation = validator.validate(result("NOT_VALIDATED", List.of(), List.of(calculation)));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
                .anyMatch(error -> error.contains("Cálculo inconsistente: margen %")));
    }

    @Test
    void rejectsDivisionByZero() {
        var calculation = new AgentResult.Calculation("punto de equilibrio", 50, 0, "DIVIDE", 0);

        var validation = validator.validate(result("NOT_VALIDATED", List.of(), List.of(calculation)));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
                .anyMatch(error -> error.contains("División por cero")), () -> String.join(", ", validation.errors()));
    }

    @Test
    void keepsExactToleranceForAddAndSubtract() {
        var calculation = new AgentResult.Calculation("costo total", 10, 5, "ADD", 15.004);

        var validation = validator.validate(result("NOT_VALIDATED", List.of(), List.of(calculation)));

        assertFalse(validation.valid());
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

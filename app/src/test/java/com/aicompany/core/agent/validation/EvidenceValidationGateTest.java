package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.AgentResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceValidationGateTest {

    private final EvidenceValidationGate gate = new EvidenceValidationGate();

    @Test
    void acceptsResultWithoutEvidence() {
        var validation = gate.validate(result(List.of()));

        assertTrue(validation.valid());
    }

    @Test
    void acceptsUnverifiedEvidenceRegardlessOfSourceShape() {
        var evidence = new AgentResult.Evidence(
                "Posible estudio de mercado",
                "quizás una fuente futura",
                "WEB",
                false
        );

        var validation = gate.validate(result(List.of(evidence)));

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void acceptsVerifiedWebEvidenceWithRealLookingUrl() {
        var evidence = new AgentResult.Evidence(
                "Pedido confirmado",
                "https://example.com/orders/123",
                "WEB",
                true
        );

        var validation = gate.validate(result(List.of(evidence)));

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void acceptsVerifiedCustomerEvidenceWithoutUrlRequirement() {
        var evidence = new AgentResult.Evidence(
                "Cliente confirmó la compra por WhatsApp",
                "Conversación con cliente #42",
                "CUSTOMER",
                true
        );

        var validation = gate.validate(result(List.of(evidence)));

        assertTrue(validation.valid(), () -> String.join(", ", validation.errors()));
    }

    @Test
    void rejectsUnknownSourceType() {
        var evidence = new AgentResult.Evidence(
                "Algo",
                "algo",
                "RUMOR",
                false
        );

        var validation = gate.validate(result(List.of(evidence)));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
                .anyMatch(e -> e.contains("sourceType desconocido")));
    }

    @Test
    void rejectsVerifiedEvidenceWithSourceTypeNone() {
        var evidence = new AgentResult.Evidence(
                "El negocio funcionará",
                "",
                "NONE",
                true
        );

        var validation = gate.validate(result(List.of(evidence)));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
                .anyMatch(e -> e.contains("sourceType=NONE")));
    }

    @Test
    void rejectsVerifiedWebEvidenceWithoutRealUrl() {
        var evidence = new AgentResult.Evidence(
                "Estudio de mercado citado de memoria",
                "un informe que leí en algún lado",
                "WEB",
                true
        );

        var validation = gate.validate(result(List.of(evidence)));

        assertFalse(validation.valid());
        assertTrue(validation.errors().stream()
                .anyMatch(e -> e.contains("no parece una URL real")));
    }

    private AgentResult result(List<AgentResult.Evidence> evidence) {

        return new AgentResult(
                "finance",
                "UNIT_ECONOMICS",
                "PARTIALLY_VALIDATED",
                List.of(),
                List.of(),
                List.of(),
                evidence,
                List.of(),
                List.of(),
                List.of(),
                "recomendación",
                0.5
        );
    }
}

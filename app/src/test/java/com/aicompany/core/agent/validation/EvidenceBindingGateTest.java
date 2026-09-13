package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.AgentResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceBindingGateTest {

    private final EvidenceBindingGate gate = new EvidenceBindingGate();

    @Test
    void passesWhenNoEvidenceWasConfirmed() {
        var result = agentResult(List.of());

        var binding = gate.check(List.of(), result);

        assertTrue(binding.bound());
        assertTrue(binding.errors().isEmpty());
    }

    @Test
    void passesWhenConfirmedEvidenceUrlsIsNull() {
        var result = agentResult(List.of());

        var binding = gate.check(null, result);

        assertTrue(binding.bound());
    }

    @Test
    void passesWhenAgentCitesAConfirmedUrl() {
        var result = agentResult(List.of(
                new AgentResult.Evidence("desc", "https://example.com/a", "WEB", false)
        ));

        var binding = gate.check(List.of("https://example.com/a"), result);

        assertTrue(binding.bound());
    }

    @Test
    void passesWhenAgentCitesAtLeastOneOfSeveralConfirmedUrls() {
        var result = agentResult(List.of(
                new AgentResult.Evidence("desc", "https://example.com/b", "WEB", false)
        ));

        var binding = gate.check(
                List.of("https://example.com/a", "https://example.com/b"), result);

        assertTrue(binding.bound());
    }

    @Test
    void rejectsWhenEvidenceListIsEmptyDespiteConfirmedUrls() {
        var result = agentResult(List.of());

        var binding = gate.check(List.of("https://example.com/a"), result);

        assertFalse(binding.bound());
        assertFalse(binding.errors().isEmpty());
        assertTrue(binding.errors().get(0).contains("no cita"));
    }

    @Test
    void rejectsWhenEvidenceCitesUnrelatedUrls() {
        var result = agentResult(List.of(
                new AgentResult.Evidence("desc", "https://otra-fuente.com/x", "WEB", false)
        ));

        var binding = gate.check(List.of("https://example.com/a"), result);

        assertFalse(binding.bound());
    }

    @Test
    void isCaseAndTrailingSlashInsensitiveWhenMatching() {
        var result = agentResult(List.of(
                new AgentResult.Evidence("desc", "HTTPS://Example.com/A/", "WEB", false)
        ));

        var binding = gate.check(List.of("https://example.com/a"), result);

        assertTrue(binding.bound());
    }

    @Test
    void ignoresNullSourcesInEvidenceListWithoutThrowing() {
        var result = agentResult(List.of(
                new AgentResult.Evidence("desc", null, "NONE", false)
        ));

        assertDoesNotThrow(() -> gate.check(List.of("https://example.com/a"), result));
        assertFalse(gate.check(List.of("https://example.com/a"), result).bound());
    }

    private AgentResult agentResult(List<AgentResult.Evidence> evidence) {
        return new AgentResult(
                "sales", "ACTION", "NOT_VALIDATED",
                List.of(), List.of(), List.of(), evidence, List.of(), List.of(), List.of(),
                "recomendación", 0.5
        );
    }
}

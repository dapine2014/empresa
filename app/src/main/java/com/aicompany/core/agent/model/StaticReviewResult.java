package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Objects;

/**
 * Revisión ESTÁTICA del código generado (capa 2 de la validación, spec §7).
 * El estado final ({@code StaticValidationStatus}) lo calcula Java, no este record.
 */
public record StaticReviewResult(
        String verdict,
        List<Finding> findings,
        List<String> missingFiles,
        String architectureConsistency,
        List<String> notValidatableWithoutExecution,
        List<AgentResult.Evidence> evidence
) {

    public record Finding(String path, String severity, String description) {
    }

    public List<Finding> findingsOrEmpty() {
        return findings == null ? List.of() : findings;
    }

    public boolean hasBlocker() {
        return findingsOrEmpty().stream()
                .filter(Objects::nonNull)
                .anyMatch(f -> "BLOCKER".equals(f.severity()));
    }
}

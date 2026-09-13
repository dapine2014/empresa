package com.aicompany.core.agent.model;

import java.util.List;

public record AgentResult(
        String agent,
        String action,
        String verificationStatus,
        List<String> facts,
        List<String> hypotheses,
        List<String> estimates,
        List<Evidence> evidence,
        List<String> evidenceRequired,
        List<Calculation> calculations,
        List<String> risks,
        String recommendation,
        double confidence
) {

    public record Evidence(
            String description,
            String source,
            String sourceType,
            boolean verified
    ) {
    }

    public record Calculation(
            String name,
            double inputA,
            double inputB,
            String operation,
            double result
    ) {
    }

    public static AgentResult empty(String agent, String action) {
        return new AgentResult(
                agent,
                action,
                "NOT_VALIDATED",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                0.0
        );
    }
}

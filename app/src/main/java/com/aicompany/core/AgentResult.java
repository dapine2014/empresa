package com.aicompany.core.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * {@code NON_EMPTY} recorta, al serializar (no al leer), los campos
 * null/blank y las listas vacías — reduce el tamaño del prompt de
 * consolidación del CEO (que recibe estos resultados como JSON) y el
 * tamaño de lo que se persiste en Neo4j, sin afectar el parseo ni la
 * validación (que operan sobre el objeto ya deserializado).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record AgentResult(
        String agent,
        String action,
        String verificationStatus,
        @JsonDeserialize(contentUsing = LenientStringDeserializer.class) List<String> facts,
        @JsonDeserialize(contentUsing = LenientStringDeserializer.class) List<String> hypotheses,
        @JsonDeserialize(contentUsing = LenientStringDeserializer.class) List<String> estimates,
        List<Evidence> evidence,
        @JsonDeserialize(contentUsing = LenientStringDeserializer.class) List<String> evidenceRequired,
        List<Calculation> calculations,
        @JsonDeserialize(contentUsing = LenientStringDeserializer.class) List<String> risks,
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

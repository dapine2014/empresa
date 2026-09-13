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
        double confidence,
        List<CustomerCandidate> customerCandidates
) {

    /**
     * Constructor de compatibilidad sin {@code customerCandidates} (queda
     * vacío) — evita tocar cada uno de los {@code new AgentResult(...)} ya
     * existentes en los tests con las 12 posiciones originales, solo por
     * agregar un campo que casi ningún agente/acción va a poblar.
     */
    public AgentResult(
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
            double confidence) {

        this(
                agent, action, verificationStatus,
                facts, hypotheses, estimates,
                evidence, evidenceRequired, calculations, risks,
                recommendation, confidence,
                List.of()
        );
    }

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

    /**
     * Candidato de cliente (LEAD/PROSPECT) identificado por un agente
     * durante su investigación — nunca un cliente real ni verificado
     * (para eso existe {@code CustomerController}, canal humano con
     * evidencia obligatoria). Deliberadamente sin campo {@code verified}:
     * un candidato de agente jamás se marca verificado, es una hipótesis
     * de a quién vender, no una relación real confirmada.
     */
    public record CustomerCandidate(
            String name,
            String description,
            String source,
            String sourceType
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

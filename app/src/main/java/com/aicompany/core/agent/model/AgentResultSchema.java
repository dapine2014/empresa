package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/**
 * JSON Schema formal de {@link AgentResult}, usado como valor del campo
 * "format" en las llamadas a Ollama (structured outputs) para las tareas
 * de agente, en vez del modo débil {@code "format": "json"} (que solo
 * garantiza JSON válido, no una forma compatible con el contrato).
 *
 * Debe mantenerse en sincronía manual con los campos de {@link AgentResult}
 * y sus records anidados ({@link AgentResult.Evidence},
 * {@link AgentResult.Calculation}).
 */
public final class AgentResultSchema {

    private AgentResultSchema() {
    }

    private static final List<String> VERIFICATION_STATUSES = List.of(
            "NOT_VALIDATED",
            "PARTIALLY_VALIDATED",
            "VALIDATED"
    );

    private static final List<String> CALCULATION_OPERATIONS = List.of(
            "ADD",
            "SUBTRACT"
    );

    /**
     * Debe coincidir exactamente con
     * {@link com.aicompany.core.agent.validation.EvidenceValidationGate}
     * — si no, el modelo puede generar un {@code sourceType} sintácticamente
     * válido para el schema pero que el gate rechaza siempre (reproducido en
     * vivo: con tool-calling real el modelo devolvió "academic repository",
     * "blog post", etc. antes de este `enum`).
     */
    private static final List<String> EVIDENCE_SOURCE_TYPES = List.of(
            "WEB", "CUSTOMER", "TRANSACTION", "INTERNAL", "NONE"
    );

    private static final Map<String, Object> EVIDENCE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "description", Map.of("type", "string", "minLength", 1),
                    "source", Map.of("type", "string"),
                    "sourceType", Map.of(
                            "type", "string",
                            "enum", EVIDENCE_SOURCE_TYPES
                    ),
                    "verified", Map.of("type", "boolean")
            ),
            "required", List.of(
                    "description",
                    "source",
                    "sourceType",
                    "verified"
            ),
            "additionalProperties", false
    );

    /**
     * Debe coincidir con {@link AgentResult.CustomerCandidate}. Reutiliza
     * {@code EVIDENCE_SOURCE_TYPES}: es el mismo concepto de "de dónde
     * salió este dato" que en `Evidence`, no uno nuevo.
     */
    private static final Map<String, Object> CUSTOMER_CANDIDATE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of("type", "string", "minLength", 1),
                    "description", Map.of("type", "string", "minLength", 1),
                    "source", Map.of("type", "string"),
                    "sourceType", Map.of(
                            "type", "string",
                            "enum", EVIDENCE_SOURCE_TYPES
                    )
            ),
            "required", List.of(
                    "name",
                    "description",
                    "source",
                    "sourceType"
            ),
            "additionalProperties", false
    );

    private static final Map<String, Object> CALCULATION_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of("type", "string", "minLength", 1),
                    "inputA", Map.of("type", "number"),
                    "inputB", Map.of("type", "number"),
                    "operation", Map.of(
                            "type", "string",
                            "enum", CALCULATION_OPERATIONS
                    ),
                    "result", Map.of("type", "number")
            ),
            "required", List.of(
                    "name",
                    "inputA",
                    "inputB",
                    "operation",
                    "result"
            ),
            "additionalProperties", false
    );

    private static Map<String, Object> stringArray() {
        return Map.of(
                "type", "array",
                "items", Map.of("type", "string")
        );
    }

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.ofEntries(
                    Map.entry("agent", Map.of("type", "string", "minLength", 1)),
                    Map.entry("action", Map.of("type", "string", "minLength", 1)),
                    Map.entry("verificationStatus", Map.of(
                            "type", "string",
                            "enum", VERIFICATION_STATUSES
                    )),
                    Map.entry("facts", stringArray()),
                    Map.entry("hypotheses", stringArray()),
                    Map.entry("estimates", stringArray()),
                    Map.entry("evidence", Map.of(
                            "type", "array",
                            "items", EVIDENCE_ITEM_SCHEMA
                    )),
                    Map.entry("evidenceRequired", stringArray()),
                    Map.entry("calculations", Map.of(
                            "type", "array",
                            "items", CALCULATION_ITEM_SCHEMA
                    )),
                    Map.entry("risks", stringArray()),
                    Map.entry("recommendation", Map.of("type", "string", "minLength", 1)),
                    Map.entry("confidence", Map.of("type", "number")),
                    Map.entry("customerCandidates", Map.of(
                            "type", "array",
                            "items", CUSTOMER_CANDIDATE_ITEM_SCHEMA
                    ))
            ),
            "required", List.of(
                    "agent",
                    "action",
                    "verificationStatus",
                    "facts",
                    "hypotheses",
                    "estimates",
                    "evidence",
                    "evidenceRequired",
                    "calculations",
                    "risks",
                    "recommendation",
                    "confidence",
                    "customerCandidates"
            ),
            "additionalProperties", false
    );
}

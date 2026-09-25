package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/** JSON Schema formal de {@link StaticReviewResult}. */
public final class StaticReviewResultSchema {

    private StaticReviewResultSchema() {
    }

    private static final Map<String, Object> STRING_ARRAY =
            Map.of("type", "array", "items", Map.of("type", "string"));

    private static final Map<String, Object> FINDING_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of("type", "string"),
                    "severity", Map.of("type", "string", "enum", List.of("BLOCKER", "MAJOR", "MINOR")),
                    "description", Map.of("type", "string", "minLength", 1)
            ),
            "required", List.of("path", "severity", "description"),
            "additionalProperties", false
    );

    private static final Map<String, Object> EVIDENCE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "description", Map.of("type", "string", "minLength", 1),
                    "source", Map.of("type", "string"),
                    "sourceType", Map.of("type", "string",
                            "enum", List.of("WEB", "CUSTOMER", "TRANSACTION", "INTERNAL", "NONE")),
                    "verified", Map.of("type", "boolean")
            ),
            "required", List.of("description", "source", "sourceType", "verified"),
            "additionalProperties", false
    );

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "verdict", Map.of("type", "string", "enum", List.of("NO_EVIDENT_ISSUES", "ISSUES_FOUND")),
                    "findings", Map.of("type", "array", "items", FINDING_SCHEMA),
                    "missingFiles", STRING_ARRAY,
                    "architectureConsistency", Map.of("type", "string", "minLength", 1),
                    "notValidatableWithoutExecution", Map.of(
                            "type", "array", "items", Map.of("type", "string"), "minItems", 1),
                    "evidence", Map.of("type", "array", "items", EVIDENCE_SCHEMA, "minItems", 1)
            ),
            "required", List.of("verdict", "findings", "missingFiles", "architectureConsistency",
                    "notValidatableWithoutExecution", "evidence"),
            "additionalProperties", false
    );
}

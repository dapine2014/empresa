package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/**
 * JSON Schema formal de {@link DevelopmentResult}, mismo patrón que
 * {@link AgentResultSchema} — usado como valor del campo "format" en la
 * llamada a Ollama para forzar la forma exacta del contrato.
 */
public final class DevelopmentResultSchema {

    private DevelopmentResultSchema() {
    }

    private static final Map<String, Object> FILE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of("type", "string", "minLength", 1),
                    "content", Map.of("type", "string", "minLength", 1)
            ),
            "required", List.of("path", "content"),
            "additionalProperties", false
    );

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string", "minLength", 1),
                    "files", Map.of(
                            "type", "array",
                            "items", FILE_ITEM_SCHEMA,
                            "minItems", 1
                    )
            ),
            "required", List.of("summary", "files"),
            "additionalProperties", false
    );
}

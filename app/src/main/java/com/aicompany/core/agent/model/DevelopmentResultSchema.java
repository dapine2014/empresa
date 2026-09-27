package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/** JSON Schema formal de {@link DevelopmentResult}, usado como "format" en Ollama. */
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

    /** Parte 3: paquete NuGet pedido por el agente, con versión exacta. */
    private static final Map<String, Object> PACKAGE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of("type", "string", "minLength", 1),
                    "version", Map.of("type", "string", "minLength", 1)
            ),
            "required", List.of("name", "version"),
            "additionalProperties", false
    );

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string", "minLength", 1),
                    "files", Map.of("type", "array", "items", FILE_ITEM_SCHEMA, "minItems", 1),
                    "packages", Map.of("type", "array", "items", PACKAGE_ITEM_SCHEMA)
            ),
            "required", List.of("summary", "files"),
            "additionalProperties", false
    );
}

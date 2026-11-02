package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/** Spec 2026-11-01 front con diseño visual: lo que el front entrega en una sola respuesta (sistema visual + maquetas). */
public record VisualDesign(VisualSystem system, Mockups mockups) {

    public static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of("system", VisualSystem.SCHEMA, "mockups", Mockups.SCHEMA),
            "required", List.of("system", "mockups"));
}

package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/** Spec 2026-11-01 front con diseño visual: el sistema visual (paleta, tipografía, componentes) que escribe el front. */
public record VisualSystem(List<ColorToken> colors, Typography typography, Map<String, Double> spacing,
                           Map<String, Double> radii, List<ComponentStyle> components) {

    public VisualSystem {
        colors = colors == null ? List.of() : colors;
        spacing = spacing == null ? Map.of() : spacing;
        radii = radii == null ? Map.of() : radii;
        components = components == null ? List.of() : components;
    }

    public List<ColorToken> colorsOrEmpty() { return colors; }
    public List<ComponentStyle> componentsOrEmpty() { return components; }

    public record ColorToken(String name, String hex) { }

    /** sizes con claves display, title, body y label. */
    public record Typography(String fontFamily, Map<String, Double> sizes) {
        public Typography { sizes = sizes == null ? Map.of() : sizes; }
    }

    /** component ∈ {button, field, card, appBar}; los valores de tokens son nombres de color. */
    public record ComponentStyle(String component, Map<String, String> tokens) {
        public ComponentStyle { tokens = tokens == null ? Map.of() : tokens; }
    }

    public static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of(
                    "colors", InteractionDesign.array(Map.of("type", "object",
                            "properties", Map.of("name", InteractionDesign.str(), "hex", InteractionDesign.str()),
                            "required", List.of("name", "hex"))),
                    "typography", Map.of("type", "object",
                            "properties", Map.of("fontFamily", InteractionDesign.str(),
                                    "sizes", Map.of("type", "object", "additionalProperties", Map.of("type", "number"))),
                            "required", List.of("fontFamily", "sizes")),
                    "spacing", Map.of("type", "object", "additionalProperties", Map.of("type", "number")),
                    "radii", Map.of("type", "object", "additionalProperties", Map.of("type", "number")),
                    "components", InteractionDesign.array(Map.of("type", "object",
                            "properties", Map.of("component", InteractionDesign.str(),
                                    "tokens", Map.of("type", "object", "additionalProperties", InteractionDesign.str())),
                            "required", List.of("component", "tokens")))),
            "required", List.of("colors", "typography", "spacing", "radii", "components"));
}

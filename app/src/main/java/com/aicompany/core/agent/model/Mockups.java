package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/** Spec 2026-11-01 front con diseño visual: una maqueta HTML/CSS por pantalla de Kael y los SVG que usan. */
public record Mockups(List<Mockup> screens, List<SvgAsset> svgs) {

    public Mockups {
        screens = screens == null ? List.of() : screens;
        svgs = svgs == null ? List.of() : svgs;
    }

    /** screen = id de pantalla del diseño (PANT-NN). */
    public record Mockup(String screen, String html) { }

    /** name = [a-z0-9_]+. */
    public record SvgAsset(String name, String svg) { }

    public static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "properties", Map.of(
                    "screens", InteractionDesign.array(Map.of("type", "object",
                            "properties", Map.of("screen", InteractionDesign.str(), "html", InteractionDesign.str()),
                            "required", List.of("screen", "html"))),
                    "svgs", InteractionDesign.array(Map.of("type", "object",
                            "properties", Map.of("name", InteractionDesign.str(), "svg", InteractionDesign.str()),
                            "required", List.of("name", "svg")))),
            "required", List.of("screens", "svgs"));
}

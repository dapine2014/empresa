package com.aicompany.core.agent.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;
import java.util.Map;

/**
 * Revisión conjunta (spec 2026-10-15, decisión del fundador: "cuando se generan contradicciones el líder técnico decide
 * junto con el PO"): Neo detecta las contradicciones entre las HU de Aria y el diseño de Kael y propone; Aria decide.
 */
public final class JointReview {

    public static final String DESIGN = "DESIGN";
    public static final String REQUIREMENTS = "REQUIREMENTS";

    private JointReview() {
    }

    public record Proposal(String side, String change) {
    }

    public record Contradiction(String id,
                                @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> requirementRefs,
                                @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> designRefs,
                                String requirementSays, String designSays, Proposal proposal) {
        public Contradiction {
            requirementRefs = requirementRefs == null ? List.of() : requirementRefs;
            designRefs = designRefs == null ? List.of() : designRefs;
        }
    }

    public record Detection(List<Contradiction> contradictions) {
        public Detection {
            contradictions = contradictions == null ? List.of() : contradictions;
        }

        public static final Map<String, Object> SCHEMA = Map.of("type", "object",
                "properties", Map.of("contradictions", Map.of("type", "array", "items", Map.of("type", "object",
                        "properties", Map.of(
                                "id", str(),
                                "requirementRefs", strings(),
                                "designRefs", strings(),
                                "requirementSays", str(),
                                "designSays", str(),
                                "proposal", Map.of("type", "object", "properties", Map.of(
                                                "side", Map.of("type", "string", "enum", List.of(DESIGN, REQUIREMENTS)),
                                                "change", str()),
                                        "required", List.of("side", "change"))),
                        "required", List.of("id", "requirementRefs", "designRefs", "requirementSays", "designSays",
                                "proposal")))),
                "required", List.of("contradictions"));
    }

    public record Decision(String id, String side, String change, String reason) {
    }

    public record Resolution(List<Decision> decisions) {
        public Resolution {
            decisions = decisions == null ? List.of() : decisions;
        }

        public static final Map<String, Object> SCHEMA = Map.of("type", "object",
                "properties", Map.of("decisions", Map.of("type", "array", "items", Map.of("type", "object",
                        "properties", Map.of(
                                "id", str(),
                                "side", Map.of("type", "string", "enum", List.of(DESIGN, REQUIREMENTS)),
                                "change", str(),
                                "reason", str()),
                        "required", List.of("id", "side", "change", "reason")))),
                "required", List.of("decisions"));
    }

    private static Map<String, Object> str() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> strings() {
        return Map.of("type", "array", "items", Map.of("type", "string"));
    }
}

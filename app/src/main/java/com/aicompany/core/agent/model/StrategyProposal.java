package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/**
 * Spec misiones durables §3 (revisión 2026-10-06): la estrategia distinta que Neo propone tras leer lo que Java buscó en
 * internet. {@code sources}: URL de las páginas leídas en las que se apoya; Java exige que existan y que no repita una
 * estrategia ya intentada para ese error.
 */
public record StrategyProposal(String strategy, List<String> sources) {

    public String strategyOrEmpty() {
        return strategy == null ? "" : strategy;
    }

    public List<String> sourcesOrEmpty() {
        return sources == null ? List.of() : sources;
    }

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "strategy", Map.of("type", "string"),
                    "sources", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", List.of("strategy", "sources"));
}

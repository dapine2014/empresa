package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/** JSON Schema formal de {@link TeamPlan}. stackProfile vacío y listas vacías en equipos de análisis. */
public final class TeamPlanSchema {

    private TeamPlanSchema() {
    }

    private static final Map<String, Object> TASK_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "agentId", Map.of("type", "string", "minLength", 1),
                    "kind", Map.of("type", "string", "enum", List.of(TeamPlan.KIND_WORK, TeamPlan.KIND_VALIDATION)),
                    "action", Map.of("type", "string", "minLength", 1),
                    "objective", Map.of("type", "string", "minLength", 1),
                    "requiredCapabilities", Map.of(
                            "type", "array", "items", Map.of("type", "string"), "minItems", 1),
                    "ownedPaths", Map.of("type", "array", "items", Map.of("type", "string"))
            ),
            "required", List.of("agentId", "kind", "action", "objective", "requiredCapabilities", "ownedPaths"),
            "additionalProperties", false
    );

    private static final Map<String, Object> PARTICIPATION_CONFLICTS_SCHEMA = Map.of("type", "array", "items", Map.of(
            "type", "object",
            "properties", Map.of(
                    "agentId", Map.of("type", "string", "minLength", 1),
                    "reason", Map.of("type", "string", "minLength", 1)),
            "required", List.of("agentId", "reason"),
            "additionalProperties", false));

    private static final Map<String, Object> CONTEXT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of("type", "string", "minLength", 1),
                    "description", Map.of("type", "string", "minLength", 1)),
            "required", List.of("name", "description"),
            "additionalProperties", false);

    private static final Map<String, Object> TERM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "term", Map.of("type", "string", "minLength", 1),
                    "definition", Map.of("type", "string", "minLength", 1)),
            "required", List.of("term", "definition"),
            "additionalProperties", false);

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string", "minLength", 1),
                    "stackProfile", Map.of("type", "string"),
                    "boundedContexts", Map.of("type", "array", "items", CONTEXT_SCHEMA),
                    "ubiquitousLanguage", Map.of("type", "array", "items", TERM_SCHEMA),
                    "tasks", Map.of("type", "array", "items", TASK_SCHEMA, "minItems", 1),
                    "participationConflicts", PARTICIPATION_CONFLICTS_SCHEMA
            ),
            "required", List.of("summary", "stackProfile", "boundedContexts", "ubiquitousLanguage", "tasks"),
            "additionalProperties", false
    );
}

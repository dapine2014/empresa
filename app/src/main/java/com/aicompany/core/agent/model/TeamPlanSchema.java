package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/** JSON Schema formal de {@link TeamPlan}. techStack/entryPoint pueden ir vacíos (equipos de análisis). */
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

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string", "minLength", 1),
                    "techStack", Map.of("type", "string"),
                    "entryPoint", Map.of("type", "string"),
                    "tasks", Map.of("type", "array", "items", TASK_SCHEMA, "minItems", 1),
                    "participationConflicts", Map.of("type", "array", "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "agentId", Map.of("type", "string", "minLength", 1),
                                    "reason", Map.of("type", "string", "minLength", 1)),
                            "required", List.of("agentId", "reason"),
                            "additionalProperties", false))
            ),
            "required", List.of("summary", "techStack", "entryPoint", "tasks"),
            "additionalProperties", false
    );
}

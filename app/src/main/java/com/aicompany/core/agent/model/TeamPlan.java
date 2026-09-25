package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plan de trabajo del líder de un equipo ({@code TeamWorkPlanner}). Nunca
 * se usa sin pasar antes por {@code TeamPlanValidator}. Ver spec §3-4.
 */
public record TeamPlan(
        String summary,
        String techStack,
        String entryPoint,
        List<PlannedTask> tasks
) {

    public static final String KIND_WORK = "WORK";
    public static final String KIND_VALIDATION = "VALIDATION";

    public record PlannedTask(
            String agentId,
            String kind,
            String action,
            String objective,
            List<String> requiredCapabilities,
            List<String> ownedPaths
    ) {
        public List<String> requiredCapabilitiesOrEmpty() {
            return requiredCapabilities == null ? List.of() : requiredCapabilities;
        }

        public List<String> ownedPathsOrEmpty() {
            return ownedPaths == null ? List.of() : ownedPaths;
        }
    }

    public List<PlannedTask> tasksOrEmpty() {
        return tasks == null ? List.of() : tasks;
    }

    public List<PlannedTask> workTasks() {
        return tasksOrEmpty().stream()
                .filter(Objects::nonNull)
                .filter(t -> KIND_WORK.equals(t.kind()))
                .toList();
    }

    public Optional<PlannedTask> validationTask() {
        return tasksOrEmpty().stream()
                .filter(Objects::nonNull)
                .filter(t -> KIND_VALIDATION.equals(t.kind()))
                .findFirst();
    }
}

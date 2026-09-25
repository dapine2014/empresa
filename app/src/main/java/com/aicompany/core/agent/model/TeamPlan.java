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
        List<PlannedTask> tasks,
        List<ParticipationConflict> participationConflicts
) {

    /** Planes sin conflictos de participación declarados. */
    public TeamPlan(String summary, String techStack, String entryPoint, List<PlannedTask> tasks) {
        this(summary, techStack, entryPoint, tasks, List.of());
    }

    /**
     * El líder declara que un miembro no tiene trabajo real para esta misión
     * aunque la regla de participación completa lo exige: se reporta antes
     * de ejecutar, en vez de inventar trabajo artificial.
     */
    public record ParticipationConflict(String agentId, String reason) {
    }

    public List<ParticipationConflict> participationConflictsOrEmpty() {
        return participationConflicts == null ? List.of() : participationConflicts;
    }

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

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
        List<ParticipationConflict> participationConflicts,
        String stackProfile,
        List<BoundedContext> boundedContexts,
        List<GlossaryTerm> ubiquitousLanguage
) {

    /** Planes sin conflictos ni campos DDD (equipos de análisis y planes previos). */
    public TeamPlan(String summary, String techStack, String entryPoint, List<PlannedTask> tasks) {
        this(summary, techStack, entryPoint, tasks, List.of(), null, List.of(), List.of());
    }

    public TeamPlan(String summary, String techStack, String entryPoint, List<PlannedTask> tasks,
                    List<ParticipationConflict> participationConflicts) {
        this(summary, techStack, entryPoint, tasks, participationConflicts, null, List.of(), List.of());
    }

    /** Bounded context DDD del producto (spec 2026-09-26 §1). El nombre define las rutas. */
    public record BoundedContext(String name, String description) {
    }

    /** Término del lenguaje ubicuo con su definición. */
    public record GlossaryTerm(String term, String definition) {
    }

    public List<BoundedContext> boundedContextsOrEmpty() {
        return boundedContexts == null ? List.of() : boundedContexts;
    }

    public List<GlossaryTerm> ubiquitousLanguageOrEmpty() {
        return ubiquitousLanguage == null ? List.of() : ubiquitousLanguage;
    }

    public List<String> contextNames() {
        return boundedContextsOrEmpty().stream()
                .filter(Objects::nonNull)
                .map(BoundedContext::name)
                .toList();
    }

    public Optional<com.aicompany.core.model.StackProfile> profile() {
        return com.aicompany.core.model.StackProfile.parse(stackProfile);
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

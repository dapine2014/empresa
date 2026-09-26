package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StackProfile.Layer;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Java decide lo mecánico del plan de desarrollo (spec 2026-09-26 §1,
 * revisión opción B): a partir de las capas que el líder asignó a cada
 * miembro calcula los ownedPaths desde el catálogo, asigna la única tarea
 * VALIDATION al primer miembro con la capability QA, y le da al líder los
 * archivos de entrada del perfil. Verificado en vivo: qwen3:8b no convergía
 * haciendo esto él mismo. Los errores son accionables y se reintentan.
 */
@Component
public class TeamPlanResolver {

    public record Resolution(TeamPlan plan, List<String> errors) {
    }

    public Resolution resolve(TeamPlan plan, TeamSnapshot team) {

        var profile = plan == null ? Optional.<StackProfile>empty() : plan.profile();
        if (profile.isEmpty()) {
            return new Resolution(plan, List.of());
        }

        var errors = new ArrayList<String>();
        var contexts = plan.contextNames();
        var qaAgentId = team.members().stream()
                .filter(m -> m.capabilities().contains("QA"))
                .map(TeamMemberInfo::agentId)
                .findFirst()
                .orElse(null);

        var ownerByRoot = new HashMap<String, String>();
        var resolved = new ArrayList<PlannedTask>();

        for (var task : plan.tasksOrEmpty()) {

            if (task == null) {
                continue;
            }

            if (Objects.equals(task.agentId(), qaAgentId)) {
                resolved.add(withKindAndPaths(task, TeamPlan.KIND_VALIDATION, List.of()));
                continue;
            }

            var paths = new LinkedHashSet<String>();

            for (var assignment : task.assignmentsOrEmpty()) {
                resolveAssignment(profile.get(), contexts, task.agentId(), assignment, ownerByRoot, errors)
                        .ifPresent(paths::add);
            }

            if (task.assignmentsOrEmpty().isEmpty()) {
                errors.add("La tarea de " + task.agentId() + " no tiene assignments: asígnale al menos una capa "
                        + "{context, layer} de " + profile.get().name() + ".");
            }

            resolved.add(withKindAndPaths(task, TeamPlan.KIND_WORK, new ArrayList<>(paths)));
        }

        for (var context : contexts) {
            var domainRoot = profile.get().resolveRoot(context, Layer.DOMAIN);
            if (domainRoot.isPresent() && !ownerByRoot.containsKey(domainRoot.get())) {
                errors.add("La capa DOMAIN del contexto " + context + " no tiene dueño: asígnala a un miembro.");
            }
        }

        addLeaderFiles(profile.get(), team.leaderAgentId(), ownerByRoot, resolved);

        return new Resolution(new TeamPlan(plan.summary(), plan.techStack(), plan.entryPoint(), resolved,
                plan.participationConflicts(), plan.stackProfile(), plan.boundedContexts(), plan.ubiquitousLanguage()),
                errors);
    }

    private static Optional<String> resolveAssignment(
            StackProfile profile, List<String> contexts, String agentId, TeamPlan.LayerAssignment assignment,
            HashMap<String, String> ownerByRoot, List<String> errors) {

        if (assignment == null) {
            return Optional.empty();
        }

        var layer = parseLayer(assignment.layer());
        if (layer.isEmpty() || !profile.layers().contains(layer.get())) {
            errors.add("La capa \"" + assignment.layer() + "\" de " + agentId + " no existe en " + profile.name()
                    + ". Capas disponibles: " + profile.layers() + ".");
            return Optional.empty();
        }

        var shared = profile.isSharedLayer(layer.get());
        if (!shared && !contexts.contains(assignment.context())) {
            errors.add("El contexto \"" + assignment.context() + "\" asignado a " + agentId
                    + " no está en boundedContexts " + contexts + ".");
            return Optional.empty();
        }

        var root = profile.resolveRoot(assignment.context(), layer.get()).orElseThrow();
        var owner = ownerByRoot.putIfAbsent(root, agentId);

        if (owner != null && !owner.equals(agentId)) {
            var what = shared ? "La capa " + layer.get() : "La capa " + layer.get() + " del contexto " + assignment.context();
            errors.add(what + " está asignada a " + owner + " y a " + agentId + ": asígnala a uno solo.");
            return Optional.empty();
        }

        return Optional.of(root);
    }

    private static void addLeaderFiles(
            StackProfile profile, String leaderId, HashMap<String, String> ownerByRoot, List<PlannedTask> tasks) {

        for (int i = 0; i < tasks.size(); i++) {

            var task = tasks.get(i);
            if (!Objects.equals(task.agentId(), leaderId) || TeamPlan.KIND_VALIDATION.equals(task.kind())) {
                continue;
            }

            var paths = new ArrayList<>(task.ownedPathsOrEmpty());
            for (var file : profile.leaderOwnedPaths()) {
                var covered = ownerByRoot.keySet().stream().anyMatch(root -> OwnedPaths.covers(root, file));
                if (!covered && !paths.contains(file)) {
                    paths.add(file);
                }
            }

            tasks.set(i, withKindAndPaths(task, task.kind(), paths));
        }
    }

    private static Optional<Layer> parseLayer(String layer) {
        if (layer == null) {
            return Optional.empty();
        }
        var normalized = layer.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(Layer.values()).filter(l -> l.name().equals(normalized)).findFirst();
    }

    private static PlannedTask withKindAndPaths(PlannedTask task, String kind, List<String> ownedPaths) {
        return new PlannedTask(task.agentId(), kind, task.action(), task.objective(),
                task.requiredCapabilities(), List.copyOf(ownedPaths), task.assignments());
    }
}

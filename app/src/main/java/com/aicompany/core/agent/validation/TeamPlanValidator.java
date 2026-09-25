package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validación determinista del plan del líder (spec §4). Reglas comunes a
 * los 3 equipos + reglas propias de la estrategia de desarrollo. Nunca
 * llama a un modelo. Lista vacía = plan válido.
 */
@Component
public class TeamPlanValidator {

    public List<String> validate(TeamPlan plan, TeamSnapshot team, TeamExecutionMode mode) {

        var errors = new ArrayList<String>();

        if (plan == null) {
            errors.add("El plan está vacío.");
            return errors;
        }

        var membersById = new LinkedHashMap<String, TeamMemberInfo>();
        for (var member : team.members()) {
            membersById.put(member.agentId(), member);
        }

        if (plan.summary() == null || plan.summary().isBlank()) {
            errors.add("summary no puede estar vacío.");
        }

        validateCommonTaskRules(plan, team, membersById, errors);

        if (plan.workTasks().isEmpty()) {
            errors.add("El plan debe tener al menos una tarea WORK.");
        }

        var leaderHasTask = plan.tasksOrEmpty().stream()
                .anyMatch(t -> t != null && Objects.equals(t.agentId(), team.leaderAgentId()));
        if (team.leaderAgentId() != null && !leaderHasTask) {
            errors.add("El líder del equipo (" + team.leaderAgentId() + ") debe tener una tarea en el plan.");
        }

        if (mode == TeamExecutionMode.ANALYSIS) {
            if (plan.validationTask().isPresent()) {
                errors.add("Este equipo no admite tareas VALIDATION: usa kind=\"WORK\" en todas.");
            }
        } else {
            validateDevelopmentRules(plan, membersById, errors);
        }

        return errors;
    }

    private void validateCommonTaskRules(
            TeamPlan plan, TeamSnapshot team, Map<String, TeamMemberInfo> membersById, List<String> errors) {

        var seen = new HashSet<String>();

        for (var task : plan.tasksOrEmpty()) {

            if (task == null) {
                errors.add("El plan contiene una tarea nula.");
                continue;
            }

            var member = membersById.get(task.agentId());

            if (member == null) {
                errors.add("El agente \"" + task.agentId() + "\" no es miembro de " + team.teamId()
                        + ". Miembros válidos: " + membersById.keySet());
                continue;
            }

            if (!seen.add(task.agentId())) {
                errors.add("El agente " + task.agentId() + " tiene más de una tarea; asigna a lo sumo una por agente.");
            }

            if (!TeamPlan.KIND_WORK.equals(task.kind()) && !TeamPlan.KIND_VALIDATION.equals(task.kind())) {
                errors.add("kind inválido para " + task.agentId() + ": \"" + task.kind() + "\" (usa WORK o VALIDATION).");
            }

            if (task.action() == null || !task.action().matches("[A-Z_]+")) {
                errors.add("action de " + task.agentId() + " debe estar en MAYÚSCULAS_CON_GUIONES_BAJOS (recibido: \""
                        + task.action() + "\").");
            }

            if (task.objective() == null || task.objective().isBlank()) {
                errors.add("objective de " + task.agentId() + " no puede estar vacío.");
            }

            if (task.requiredCapabilitiesOrEmpty().isEmpty()) {
                errors.add("La tarea de " + task.agentId()
                        + " debe declarar requiredCapabilities copiadas textualmente de sus capabilities.");
            }

            for (var capability : task.requiredCapabilitiesOrEmpty()) {
                if (!member.capabilities().contains(capability)) {
                    errors.add(capabilityError(task.agentId(), capability, member.capabilities()));
                }
            }
        }
    }

    private void validateDevelopmentRules(
            TeamPlan plan, Map<String, TeamMemberInfo> membersById, List<String> errors) {

        var assigned = plan.tasksOrEmpty().stream()
                .filter(Objects::nonNull)
                .map(PlannedTask::agentId)
                .toList();

        for (var memberId : membersById.keySet()) {
            if (!assigned.contains(memberId)) {
                errors.add("Falta una tarea para " + memberId
                        + ": en un equipo de desarrollo todos los miembros deben recibir exactamente una tarea.");
            }
        }

        var validationTasks = plan.tasksOrEmpty().stream()
                .filter(Objects::nonNull)
                .filter(t -> TeamPlan.KIND_VALIDATION.equals(t.kind()))
                .toList();

        if (validationTasks.size() != 1) {
            errors.add("Debe haber exactamente una tarea VALIDATION (hay " + validationTasks.size() + ").");
        } else {
            var validator = membersById.get(validationTasks.get(0).agentId());
            if (validator != null && !validator.capabilities().contains("QA")) {
                errors.add("La tarea VALIDATION debe asignarse a un miembro con la capability QA; "
                        + validator.agentId() + " no la tiene.");
            }
        }

        if (plan.techStack() == null || plan.techStack().isBlank()) {
            errors.add("techStack no puede estar vacío en un equipo de desarrollo.");
        }

        if (plan.entryPoint() == null || plan.entryPoint().isBlank()) {
            errors.add("entryPoint no puede estar vacío en un equipo de desarrollo.");
        }

        var work = plan.workTasks().stream()
                .filter(t -> membersById.containsKey(t.agentId()))
                .toList();

        for (var task : work) {

            if (task.ownedPathsOrEmpty().isEmpty()) {
                errors.add("La tarea WORK de " + task.agentId() + " debe declarar ownedPaths.");
            }

            var seenPaths = new HashSet<String>();
            for (var path : task.ownedPathsOrEmpty()) {
                if (!seenPaths.add(OwnedPaths.normalize(path))) {
                    errors.add("El ownedPath \"" + path + "\" aparece dos veces en la tarea de " + task.agentId()
                            + "; cada ruta debe declararse una sola vez.");
                }
                if (path != null && path.matches(".*[*?\\[\\]{}].*")) {
                    errors.add("ownedPath con glob no permitido en " + task.agentId() + ": \"" + path
                            + "\". Usa rutas literales de archivos o carpetas (p. ej. \"web/ui\").");
                    continue;
                }
                if (!OwnedPaths.isSafe(path)) {
                    errors.add("ownedPath inseguro en " + task.agentId() + ": \"" + path
                            + "\" (sin rutas absolutas, \"..\", \"\\\" ni \".git\").");
                }
            }
        }

        for (int i = 0; i < work.size(); i++) {
            for (int j = i + 1; j < work.size(); j++) {
                var a = work.get(i);
                var b = work.get(j);
                for (var pathA : a.ownedPathsOrEmpty()) {
                    for (var pathB : b.ownedPathsOrEmpty()) {
                        if (OwnedPaths.overlap(pathA, pathB)) {
                            errors.add("Los ownedPaths \"" + pathA + "\" (" + a.agentId() + ") y \"" + pathB
                                    + "\" (" + b.agentId() + ") se solapan.");
                        }
                    }
                }
            }
        }

        if (plan.entryPoint() != null && !plan.entryPoint().isBlank()
                && work.stream().noneMatch(t -> OwnedPaths.coveredByAny(t.ownedPathsOrEmpty(), plan.entryPoint()))) {
            var ownedByAgent = work.stream()
                    .map(t -> t.agentId() + "=" + t.ownedPathsOrEmpty())
                    .collect(java.util.stream.Collectors.joining(", "));
            errors.add("entryPoint \"" + plan.entryPoint() + "\" no cae dentro de los ownedPaths de ninguna tarea WORK ("
                    + ownedByAgent + "). Cambia entryPoint por una ruta dentro de esos ownedPaths, o agrega esa "
                    + "ruta exacta a los ownedPaths de la tarea WORK que va a escribir el punto de entrada.");
        }
    }

    /**
     * Las capabilities son atómicas: si el modelo mandó varias concatenadas
     * en un solo string (verificado en vivo con Neo), el error lo dice
     * explícitamente y sugiere los elementos individuales reales.
     */
    private static String capabilityError(String agentId, String capability, List<String> realCapabilities) {

        var parts = capability == null ? List.<String>of()
                : java.util.Arrays.stream(capability.split(",")).map(String::strip).filter(p -> !p.isEmpty()).toList();
        var realParts = parts.stream().filter(realCapabilities::contains).toList();

        if (parts.size() > 1 && !realParts.isEmpty()) {
            return "La capability de " + agentId + " es una lista concatenada en un solo texto (\"" + capability
                    + "\"). requiredCapabilities es un arreglo de capabilities individuales: elige solo las necesarias, "
                    + "cada una como un elemento separado, p. ej. " + realParts.stream().limit(2)
                    .map(p -> "\"" + p + "\"").collect(java.util.stream.Collectors.joining(", ", "[", "]")) + ".";
        }

        return "La capability \"" + capability + "\" no pertenece a " + agentId
                + ". Sus capabilities reales son: " + realCapabilities;
    }
}

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
                    errors.add("La capability \"" + capability + "\" no pertenece a " + task.agentId()
                            + ". Sus capabilities reales son: " + member.capabilities());
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

            for (var path : task.ownedPathsOrEmpty()) {
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
            errors.add("entryPoint \"" + plan.entryPoint() + "\" no cae dentro de los ownedPaths de ninguna tarea WORK.");
        }
    }
}

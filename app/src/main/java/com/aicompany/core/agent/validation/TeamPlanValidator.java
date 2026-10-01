package com.aicompany.core.agent.validation;

import com.aicompany.core.model.DevelopmentPhase;
import com.aicompany.core.model.RoleLayerCatalog;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.StackProfile;
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

        validateCommonTaskRules(plan, team, membersById, errors, mode);

        if (plan.workTasks().isEmpty()) {
            errors.add("El plan debe tener al menos una tarea WORK.");
        }

        // Spec 2026-10-01 §4: en desarrollo el líder (Neo) planifica y no escribe código.
        if (mode == TeamExecutionMode.ANALYSIS) {
            var leaderHasTask = plan.tasksOrEmpty().stream()
                    .anyMatch(t -> t != null && Objects.equals(t.agentId(), team.leaderAgentId()));
            if (team.leaderAgentId() != null && !leaderHasTask) {
                errors.add("El líder del equipo (" + team.leaderAgentId() + ") debe tener una tarea en el plan.");
            }
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
            TeamPlan plan, TeamSnapshot team, Map<String, TeamMemberInfo> membersById, List<String> errors,
            TeamExecutionMode mode) {

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

            // Spec 2026-10-01 §1: QA escribe los tests (WORK) y después revisa (VALIDATION).
            var qaInDevelopment = mode == TeamExecutionMode.DEVELOPMENT && "QA".equals(member.roleCode());
            var key = qaInDevelopment ? task.agentId() + "|" + task.kind() : task.agentId();
            if (!seen.add(key)) {
                errors.add(qaInDevelopment
                        ? "El agente " + task.agentId() + " tiene más de una tarea " + task.kind()
                                + ": QA lleva exactamente una WORK (tests) y una VALIDATION (revisión)."
                        : "El agente " + task.agentId() + " tiene más de una tarea; asigna a lo sumo una por agente.");
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
                if (!isAtomic(capability)) {
                    errors.add("La capability \"" + capability + "\" de " + task.agentId() + " no es atómica: cada "
                            + "elemento de requiredCapabilities es UNA capability de su lista, sin comas ni frases "
                            + "(p. ej. " + member.capabilities().stream().limit(2).map(c -> "\"" + c + "\"")
                            .collect(java.util.stream.Collectors.joining(", ", "[", "]")) + ").");
                } else if (!member.capabilities().contains(capability)) {
                    errors.add(capabilityError(task.agentId(), capability, member.capabilities()));
                }
            }
        }
    }

    private void validateDevelopmentRules(
            TeamPlan plan, Map<String, TeamMemberInfo> membersById, List<String> errors) {

        for (var task : plan.tasksOrEmpty()) {
            if (task == null || !membersById.containsKey(task.agentId())) {
                continue;
            }
            var roleCode = membersById.get(task.agentId()).roleCode();
            var role = RoleLayerCatalog.of(roleCode);
            if (role.isEmpty()) {
                errors.add(task.agentId() + " no tiene un rol de desarrollo reconocido (roleCode " + roleCode + ").");
            } else if (!role.get().writesCode()) {
                errors.add(task.agentId() + " (" + roleCode + ") no escribe código: no le asignes tareas en el plan.");
            } else if (!RoleLayerCatalog.enabledNow(roleCode)) {
                errors.add(task.agentId() + " (" + roleCode + ") todavía no está habilitado (fase "
                        + role.get().phase() + "; fase actual " + DevelopmentPhase.CURRENT + "): no lo incluyas.");
            }
        }

        var qaIds = membersById.values().stream().filter(m -> "QA".equals(m.roleCode()))
                .map(TeamMemberInfo::agentId).toList();
        var validationTasks = plan.tasksOrEmpty().stream().filter(Objects::nonNull)
                .filter(t -> TeamPlan.KIND_VALIDATION.equals(t.kind())).toList();

        if (validationTasks.size() != 1) {
            errors.add("Debe haber exactamente una tarea VALIDATION (hay " + validationTasks.size() + ").");
        } else if (!qaIds.contains(validationTasks.get(0).agentId())) {
            errors.add("La tarea VALIDATION debe ser del miembro QA " + qaIds + ", no de "
                    + validationTasks.get(0).agentId() + ".");
        }

        for (var qaId : qaIds) {
            var hasTests = plan.workTasks().stream().anyMatch(t -> qaId.equals(t.agentId()));
            if (!hasTests) {
                errors.add("Falta la tarea WORK de tests de " + qaId + ": QA escribe un test por escenario.");
            }
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

        var profile = plan.profile();

        if (profile.isEmpty()) {
            errors.add("stackProfile debe ser uno de los perfiles del catálogo: "
                    + java.util.Arrays.toString(StackProfile.values()) + " (recibido: \"" + plan.stackProfile() + "\").");
            return;
        }

        if (!profile.get().enabledNow()) {
            errors.add("El stack " + profile.get().name() + " todavía no está habilitado (fase "
                    + profile.get().phase() + "). Stacks disponibles: FLUTTER_WEB_APP, DOTNET_APP.");
        }

        validateContextsAndGlossary(plan, profile.get(), errors);

        var contexts = plan.contextNames();
        for (var task : work) {
            for (var path : task.ownedPathsOrEmpty()) {
                if (path != null && OwnedPaths.isSafe(path) && !profile.get().isWithinStructure(path, contexts)) {
                    errors.add("El ownedPath \"" + path + "\" de " + task.agentId() + " está fuera de la estructura "
                            + "de " + profile.get().name() + ". Rutas permitidas: " + profile.get().allowedRoots(contexts)
                            + " y los archivos de entrada del perfil.");
                }
            }
        }
    }


    private static void validateContextsAndGlossary(TeamPlan plan, StackProfile profile, List<String> errors) {

        if (plan.boundedContextsOrEmpty().isEmpty()) {
            errors.add("boundedContexts debe declarar al menos un bounded context del producto (DDD).");
        }

        var seen = new HashSet<String>();
        for (var context : plan.boundedContextsOrEmpty()) {
            if (context == null || !profile.isValidContextName(context.name())) {
                errors.add("El bounded context \"" + (context == null ? null : context.name()) + "\" no cumple el formato de "
                        + profile.name() + ": " + profile.contextNameRule() + ". El nombre define las rutas de sus capas.");
            } else if (!seen.add(context.name())) {
                errors.add("El bounded context \"" + context.name() + "\" está repetido.");
            }
            if (context != null && (context.description() == null || context.description().isBlank())) {
                errors.add("El bounded context \"" + context.name() + "\" necesita una descripción.");
            }
        }

        var terms = plan.ubiquitousLanguageOrEmpty().stream()
                .filter(t -> t != null && t.term() != null && !t.term().isBlank()
                        && t.definition() != null && !t.definition().isBlank())
                .count();
        if (terms < 3) {
            errors.add("ubiquitousLanguage debe tener al menos 3 términos del dominio con su definición (hay " + terms + ").");
        }
    }

    /** Spec 2026-10-01 §4: una capability es una palabra o término corto, nunca una lista ni una frase. */
    static boolean isAtomic(String capability) {
        if (capability == null || capability.isBlank()) {
            return false;
        }
        return !capability.contains(",") && !capability.contains(";") && !capability.contains("/")
                && capability.strip().split("\\s+").length <= 3;
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

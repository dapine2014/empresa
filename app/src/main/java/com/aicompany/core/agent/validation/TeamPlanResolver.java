package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.RoleLayerCatalog;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StackProfile.Layer;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Java decide lo mecánico del plan de desarrollo (spec 2026-10-01 §4, revisión 4): Neo elige qué agentes
 * participan; las capas y rutas de cada uno salen de {@link RoleLayerCatalog}; el agente QA termina siempre con
 * una tarea WORK de tests y una VALIDATION de revisión; los archivos de entrada van al dueño de la capa más
 * externa. Nunca reescribe requiredCapabilities: si no son reales, lo rechaza el validador.
 */
@Component
public class TeamPlanResolver {

    static final String REVIEW_ACTION = "CODE_REVIEW";

    public record Resolution(TeamPlan plan, List<String> errors) {
    }

    public Resolution resolve(TeamPlan plan, TeamSnapshot team) {

        var profile = plan == null ? Optional.<StackProfile>empty() : plan.profile();
        if (profile.isEmpty()) {
            return new Resolution(plan, List.of());
        }

        var errors = new ArrayList<String>();
        var contexts = plan.contextNames();
        var membersById = new LinkedHashMap<String, TeamMemberInfo>();
        team.members().forEach(member -> membersById.put(member.agentId(), member));

        var merged = mergeRepeatedTasks(plan.tasksOrEmpty());

        var roleCodeByAgent = new LinkedHashMap<String, String>();
        for (var task : merged) {
            var member = membersById.get(task.agentId());
            if (member != null) {
                roleCodeByAgent.put(task.agentId(), member.roleCode());
            }
        }
        var components = plan.resolvedComponents();
        var multi = components.size() > 1;
        var pathsByAgent = new LinkedHashMap<String, List<String>>();
        merged.forEach(task -> pathsByAgent.put(task.agentId(), new ArrayList<>()));

        for (var component : components) {
            var componentRoles = new LinkedHashMap<String, String>();
            roleCodeByAgent.forEach((agent, role) -> {
                if (!multi || RoleLayerCatalog.writesIn(role, component.profile().ecosystem())) {
                    componentRoles.put(agent, role);
                }
            });
            var layersByAgent = RoleLayerCatalog.assign(componentRoles, component.profile().layers());
            var componentContexts = component.contexts(contexts);
            var ownerByLayerRoot = new LinkedHashMap<String, String>();

            for (var entry : layersByAgent.entrySet()) {
                for (var layer : entry.getValue()) {
                    for (var root : roots(component.profile(), componentContexts, layer)) {
                        var prefixed = component.prefix(root);
                        pathsByAgent.get(entry.getKey()).add(prefixed);
                        ownerByLayerRoot.put(prefixed, entry.getKey());
                    }
                }
            }

            for (var context : componentContexts) {
                component.profile().resolveRoot(context, Layer.DOMAIN).map(component::prefix)
                        .filter(root -> !ownerByLayerRoot.containsKey(root))
                        .ifPresent(root -> errors.add("La capa DOMAIN del contexto " + context + " no tiene dueño"
                                + (component.isRoot() ? "" : " en " + component.dir() + "/ ("
                                        + component.profile().name() + ")")
                                + ": incluye a un miembro que escriba domain (BACKEND o UI_UX)."));
            }

            addEntryFiles(component, componentContexts, ownerByLayerRoot, pathsByAgent, errors);
        }

        var resolved = new ArrayList<PlannedTask>();
        for (var task : merged) {
            var member = membersById.get(task.agentId());
            var role = member == null ? Optional.<RoleLayerCatalog.RoleLayers>empty()
                    : RoleLayerCatalog.of(member.roleCode());
            var writesCode = role.isPresent() && role.get().writesCode() && RoleLayerCatalog.enabledNow(member.roleCode());
            if (!writesCode) {
                resolved.add(task);
                continue;
            }
            var paths = pathsByAgent.get(task.agentId());
            if (paths.isEmpty()) {
                errors.add(task.agentId() + " (" + member.roleCode() + ") no tiene capas en "
                        + (multi ? components.stream().map(c -> c.profile().name()).toList().toString()
                                : components.get(0).profile().name())
                        + ": otro miembro elegido ya las cubre o el perfil no las tiene. Quítalo del plan.");
            }
            resolved.add(withKindAndPaths(task, TeamPlan.KIND_WORK, task.action(), paths));
            if ("QA".equals(member.roleCode())) {
                resolved.add(new PlannedTask(task.agentId(), TeamPlan.KIND_VALIDATION, REVIEW_ACTION,
                        "Revisar el código commiteado y los resultados reales del sandbox.",
                        task.requiredCapabilities(), List.of(), List.of()));
            }
        }

        addMigrations(plan, membersById, resolved);
        addPlaythroughs(plan, membersById, resolved);

        return new Resolution(new TeamPlan(plan.summary(), plan.techStack(), plan.entryPoint(), resolved,
                List.of(), plan.stackProfile(), plan.boundedContexts(), plan.ubiquitousLanguage(), plan.database(),
                plan.components()), errors);
    }

    private static List<String> roots(StackProfile profile, List<String> contexts, Layer layer) {
        if (profile.isSharedLayer(layer)) {
            return profile.resolveRoot(null, layer).map(List::of).orElse(List.of());
        }
        var roots = new ArrayList<String>();
        for (var context : contexts) {
            profile.resolveRoot(context, layer).ifPresent(roots::add);
        }
        return roots;
    }

    /**
     * Verificado en vivo (MISSION-SANDBOX-VERIFY-22): los archivos de entrada (composition root) van al dueño de
     * la capa más externa, que se genera después de las demás. Sin dueño de esas capas es un error del plan.
     */
    private static void addEntryFiles(com.aicompany.core.model.AppComponent component, List<String> contexts,
                                      Map<String, String> ownerByLayerRoot, Map<String, List<String>> pathsByAgent,
                                      List<String> errors) {

        var entryFiles = component.profile().leaderOwnedPaths().stream().map(component::prefix).toList();
        if (entryFiles.isEmpty()) {
            return;
        }

        String owner = null;
        for (var layer : List.of(Layer.PRESENTATION, Layer.API, Layer.GAME)) {
            for (var root : roots(component.profile(), contexts, layer)) {
                var prefixed = component.prefix(root);
                if (owner == null && ownerByLayerRoot.containsKey(prefixed)) {
                    owner = ownerByLayerRoot.get(prefixed);
                }
            }
        }

        if (owner == null) {
            errors.add("Los archivos de entrada " + entryFiles + " de " + component.profile().name() + " necesitan un "
                    + "dueño de la capa PRESENTATION, API o GAME: incluye a ese miembro en el plan.");
            return;
        }

        var paths = pathsByAgent.get(owner);
        entryFiles.stream().filter(file -> !paths.contains(file)).forEach(paths::add);
    }

    /**
     * Spec 2026-10-02 §2: con base de datos, el DATA_ARCHITECT (Diego) es dueño de db/postgres/migrations y el dueño
     * de la API (Iris) de .env.example (revisión final I-9: sin dueño se descartaba siempre).
     */
    private static void addMigrations(TeamPlan plan, Map<String, TeamMemberInfo> membersById, List<PlannedTask> tasks) {
        if (plan.databaseOrNull() == null) {
            return;
        }
        for (int i = 0; i < tasks.size(); i++) {
            var task = tasks.get(i);
            var member = membersById.get(task.agentId());
            if (member != null && "BACKEND".equals(member.roleCode()) && TeamPlan.KIND_WORK.equals(task.kind())) {
                var paths = new ArrayList<>(task.ownedPathsOrEmpty());
                if (!paths.contains(ENV_EXAMPLE)) {
                    paths.add(ENV_EXAMPLE);
                }
                tasks.set(i, withKindAndPaths(task, task.kind(), task.action(), paths));
                break;
            }
        }
        for (int i = 0; i < tasks.size(); i++) {
            var task = tasks.get(i);
            var member = membersById.get(task.agentId());
            if (member != null && "DATA_ARCHITECT".equals(member.roleCode()) && TeamPlan.KIND_WORK.equals(task.kind())) {
                var paths = new ArrayList<>(task.ownedPathsOrEmpty());
                if (!paths.contains(MIGRATIONS_ROOT)) {
                    paths.add(MIGRATIONS_ROOT);
                }
                tasks.set(i, withKindAndPaths(task, task.kind(), task.action(), paths));
                return;
            }
        }
    }

    /** Spec 2026-10-14 Kael §2.3: en un juego, Vera escribe además las partidas simuladas. */
    private static void addPlaythroughs(TeamPlan plan, Map<String, TeamMemberInfo> membersById, List<PlannedTask> resolved) {
        if (!plan.hasComponent(StackProfile.GODOT_DOTNET_GAME)) {
            return;
        }
        var prefix = plan.resolvedComponents().stream().filter(c -> c.profile() == StackProfile.GODOT_DOTNET_GAME)
                .findFirst().map(c -> c.prefix(StackProfile.PLAYTHROUGHS_ROOT)).orElse(StackProfile.PLAYTHROUGHS_ROOT);
        for (int i = 0; i < resolved.size(); i++) {
            var task = resolved.get(i);
            var member = membersById.get(task.agentId());
            if (member != null && "QA".equals(member.roleCode()) && TeamPlan.KIND_WORK.equals(task.kind())) {
                var paths = new ArrayList<>(task.ownedPathsOrEmpty());
                if (!paths.contains(prefix)) {
                    paths.add(prefix);
                }
                resolved.set(i, withKindAndPaths(task, task.kind(), task.action(), paths));
            }
        }
    }

    static final String MIGRATIONS_ROOT = "db/postgres/migrations";
    static final String ENV_EXAMPLE = ".env.example";

    /** Tareas repetidas de un mismo agente se unen (Java calcula rutas y tipos igual). */
    private static List<PlannedTask> mergeRepeatedTasks(List<PlannedTask> tasks) {

        var byAgent = new LinkedHashMap<String, PlannedTask>();

        for (var task : tasks) {
            if (task == null) {
                continue;
            }
            var previous = byAgent.get(task.agentId());
            if (previous == null) {
                byAgent.put(task.agentId(), task);
                continue;
            }
            var capabilities = new LinkedHashSet<>(previous.requiredCapabilitiesOrEmpty());
            capabilities.addAll(task.requiredCapabilitiesOrEmpty());
            var keep = TeamPlan.KIND_WORK.equals(previous.kind()) ? previous : task;
            byAgent.put(task.agentId(), new PlannedTask(keep.agentId(), TeamPlan.KIND_WORK, keep.action(),
                    previous.objective() + " / " + task.objective(), new ArrayList<>(capabilities),
                    List.of(), List.of()));
        }

        return new ArrayList<>(byAgent.values());
    }

    private static PlannedTask withKindAndPaths(PlannedTask task, String kind, String action, List<String> ownedPaths) {
        return new PlannedTask(task.agentId(), kind, Objects.requireNonNullElse(action, "WORK_ITEM"),
                task.objective(), task.requiredCapabilities(), List.copyOf(ownedPaths), List.of());
    }
}

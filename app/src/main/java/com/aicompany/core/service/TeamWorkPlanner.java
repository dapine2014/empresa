package com.aicompany.core.service;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.validation.TeamPlanResolver;
import com.aicompany.core.agent.validation.TeamPlanValidator;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.RoleLayerCatalog;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamPlanResult;
import com.aicompany.core.model.TeamSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * El líder del equipo descompone la misión en tareas para sus miembros
 * reales (spec §3). Genérico para los 3 equipos: la diferencia de reglas
 * entre análisis y desarrollo vive en TeamPlanValidator. Sin plan por
 * defecto: si el líder no logra un plan válido, la misión falla.
 */
@Service
public class TeamWorkPlanner {

    private static final Logger log = LoggerFactory.getLogger(TeamWorkPlanner.class);

    private static final int MAX_PLAN_RETRIES = 2;

    /**
     * Los planes de desarrollo tienen más reglas (perfil, contextos, glosario, carpetas exclusivas):
     * verificado en vivo que 3 intentos no alcanzaban para converger.
     */
    private static final int MAX_DEVELOPMENT_PLAN_RETRIES = 4;

    private final TeamMemoryService teamMemory;
    private final CeoService ceoService;
    private final CompanyMemoryService companyMemory;
    private final PromptMemoryService promptMemory;
    private final MissionMemoryService memory;
    private final CompanyEventPublisher events;
    private final TeamPlanValidator validator;
    private final TeamPlanResolver resolver;
    private final JsonMapper jsonMapper;
    private final String defaultAgentModel;

    public TeamWorkPlanner(
            TeamMemoryService teamMemory,
            CeoService ceoService,
            CompanyMemoryService companyMemory,
            PromptMemoryService promptMemory,
            MissionMemoryService memory,
            CompanyEventPublisher events,
            TeamPlanValidator validator,
            TeamPlanResolver resolver,
            JsonMapper jsonMapper,
            @Value("${ollama.agent-model}") String defaultAgentModel) {

        this.teamMemory = teamMemory;
        this.ceoService = ceoService;
        this.companyMemory = companyMemory;
        this.promptMemory = promptMemory;
        this.memory = memory;
        this.events = events;
        this.validator = validator;
        this.resolver = resolver;
        this.jsonMapper = jsonMapper;
        this.defaultAgentModel = defaultAgentModel;
    }

    public TeamPlanResult plan(String missionId, String teamId, String instruction, TeamExecutionMode mode) {
        return plan(missionId, teamId, instruction, mode, 0);
    }

    /** Equipo real (Neo4j) para una ronda de evidencia que reutiliza el plan guardado. */
    public TeamSnapshot teamSnapshot(String teamId) {
        return teamMemory.snapshot(teamId);
    }

    public TeamPlanResult plan(String missionId, String teamId, String instruction, TeamExecutionMode mode, int round) {

        var team = teamMemory.snapshot(teamId);

        if (team == null || !"ACTIVE".equals(team.status())) {
            throw new IllegalStateException("El equipo " + teamId + " no existe o no está ACTIVE en Company Memory.");
        }

        if (team.leaderAgentId() == null || team.members().isEmpty()) {
            throw new IllegalStateException("El equipo " + teamId + " no tiene líder o miembros en Company Memory.");
        }

        var leaderId = team.leaderAgentId();
        var taskId = com.aicompany.core.model.TaskIds.planTask(missionId, leaderId, round);

        memory.createTask(taskId, missionId, leaderId, "TEAM_PLANNING", "PLANNING");
        memory.updateTask(taskId, "RUNNING", "El líder está descomponiendo el trabajo.");
        memory.setAgentStatus(leaderId, "WORKING");

        try {

            var model = companyMemory.agentModel(leaderId, defaultAgentModel);
            var leaderPrompt = promptMemory.activePrompt(leaderId);
            var basePrompt = buildPrompt(team, instruction, mode);
            String feedback = null;
            String previousPlanJson = null;
            var maxRetries = mode == TeamExecutionMode.DEVELOPMENT ? MAX_DEVELOPMENT_PLAN_RETRIES : MAX_PLAN_RETRIES;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {

                var prompt = feedback == null ? basePrompt : basePrompt + correctionBlock(feedback, previousPlanJson);

                TeamPlan plan;

                try {
                    plan = ceoService.planTeamWork(leaderId, prompt, leaderPrompt, model);
                } catch (Exception ex) {
                    feedback = "- " + safeMessage(ex, "Respuesta no procesable.");
                    publishRejected(missionId, taskId, leaderId, attempt, feedback);
                    continue;
                }

                plan = normalizeActions(plan);

                var errors = new ArrayList<String>(invalidParticipationConflicts(plan, team));

                if (errors.isEmpty() && !plan.participationConflictsOrEmpty().isEmpty()) {
                    return reportParticipationConflict(missionId, taskId, leaderId, teamId, plan);
                }

                if (errors.isEmpty() && mode == TeamExecutionMode.DEVELOPMENT) {
                    var resolution = resolver.resolve(plan, team);
                    errors.addAll(resolution.errors());
                    plan = resolution.plan();
                }

                if (errors.isEmpty()) {
                    errors.addAll(validator.validate(plan, team, mode));
                }

                if (errors.isEmpty()) {

                    memory.updateTask(taskId, "COMPLETED", toJson(plan));

                    events.publish("EMPRESA_TEAM_PLAN_CREATED", missionId, taskId, leaderId,
                            Map.of("teamId", teamId, "tasks", plan.tasksOrEmpty().size()));

                    log.info("MISSION {} - team plan accepted team={} tasks={}",
                            missionId, teamId, plan.tasksOrEmpty().size());

                    return new TeamPlanResult(team, plan);
                }

                feedback = "- " + String.join("\n- ", errors);
                previousPlanJson = toJson(plan);
                publishRejected(missionId, taskId, leaderId, attempt, feedback);
            }

            var message = "El líder " + leaderId + " no produjo un plan válido para " + teamId
                    + " después de " + (maxRetries + 1) + " intentos:\n" + feedback;

            memory.updateTask(taskId, "FAILED", message);

            throw new IllegalStateException(message);

        } finally {
            memory.setAgentStatus(leaderId, "IDLE");
        }
    }

    private void publishRejected(String missionId, String taskId, String leaderId, int attempt, String feedback) {
        log.warn("MISSION {} - team plan rejected attempt={} errors={}", missionId, attempt + 1, feedback);
        events.publish("EMPRESA_TEAM_PLAN_REJECTED", missionId, taskId, leaderId,
                Map.of("attempt", attempt + 1, "errors", feedback));
    }

    private String buildPrompt(TeamSnapshot team, String instruction, TeamExecutionMode mode) {

        var leaderName = team.members().stream()
                .filter(m -> m.agentId().equals(team.leaderAgentId()))
                .map(TeamMemberInfo::name)
                .findFirst()
                .orElse(team.leaderAgentId());

        var roster = team.members().stream()
                .map(m -> "- agentId=" + m.agentId() + " | nombre=" + m.name() + " | rol=" + m.role()
                        + " | roleCode=" + m.roleCode() + " | capabilities=" + quotedList(m.capabilities()))
                .collect(Collectors.joining("\n"));

        var common = """
                Eres %s, líder de %s (%s) en Forjai. Descompón la misión en tareas para los miembros
                REALES de tu equipo.

                MISIÓN:
                %s

                MIEMBROS DEL EQUIPO (datos reales de Company Memory; no existen otros):
                %s

                REGLAS DEL PLAN:
                - Usa solo agentId de la lista anterior. Nunca asignes tareas a agentes fuera del equipo.
                - A lo sumo una tarea por agente.
                - requiredCapabilities: arreglo de capabilities del agente asignado, cada una como un elemento separado y
                  copiada TEXTUALMENTE de su lista (p. ej. ["frontend", "UI"]). Nunca inventes una.
                  Selecciona capabilities individuales del roster.
                  No copies ni concatenes el listado completo de capabilities.
                - action: identificador corto en MAYÚSCULAS_CON_GUIONES_BAJOS.
                - objective: qué debe entregar ese agente, concreto y verificable.
                """.formatted(leaderName, team.teamName(), team.teamId(), instruction, roster);

        if (mode == TeamExecutionMode.ANALYSIS) {
            return common + """
                    - kind: siempre "WORK" (este equipo no tiene tareas de validación).
                    - stackProfile: "" ; boundedContexts: [] ; ubiquitousLanguage: [] ; ownedPaths: [].
                    """;
        }

        return common + """
                - Este equipo produce CÓDIGO REAL en un repositorio Git: cada miembro recibe exactamente una tarea (nunca dos
                  tareas para el mismo agentId).
                - No inventes trabajo artificial: si el objetivo no requiere trabajo real de algún miembro, no le crees una
                  tarea de relleno; decláralo en participationConflicts con agentId y el motivo, y el plan se reportará al
                  fundador antes de ejecutar nada.
                - ownedPaths: rutas literales (sin *, ?, [ ]); cada ruta pertenece a una sola tarea y aparece una sola vez.
                - Exactamente una tarea kind="VALIDATION", asignada al miembro que tenga la capability "QA":
                  revisará el código sin ejecutarlo. Esa tarea lleva ownedPaths [].
                - Las demás tareas son kind="WORK" y declaran ownedPaths: rutas relativas (carpetas o archivos) que solo
                  ese agente puede escribir. Los ownedPaths de agentes distintos no pueden solaparse.
                  Nunca uses rutas absolutas, "..", "\\" ni ".git".
                - Metodología obligatoria: DDD.
                - stackProfile: elige EXACTAMENTE uno de estos perfiles del catálogo (no existen otros):
                %s
                - boundedContexts: los bounded contexts del producto, cada uno con name (en el formato del perfil) y
                  description. El name define las rutas de sus capas.
                - ubiquitousLanguage: al menos 3 términos del dominio, cada uno con term y definition.
                - Cada miembro tiene UNA sola tarea (nunca dos tareas para el mismo agentId).
                - Las capas de cada miembro las asigna Forjai según su rol (no las decides tú): %s.
                  Tú decides el objective de cada miembro, coherente con esas capas y con el producto.
                - kind, ownedPaths y assignments los calcula Forjai: pon kind "WORK", ownedPaths [] y
                  assignments [] en todas las tareas.
                - requiredCapabilities: solo capabilities que figuren en la lista de ESE agente (el nombre de una
                  tecnología, como "Godot", no es una capability si no está en su lista).
                - Reglas de capas: domain no depende de nada fuera de su domain ni de frameworks; application solo de
                  domain; infrastructure/api/presentation/game dependen de application y domain.
                """.formatted(StackProfile.describeAll(), RoleLayerCatalog.describe());
    }

    /**
     * El líder declaró que la regla de participación choca con el objetivo:
     * no se reintenta ni se ejecuta nada, se reporta al fundador (la misión
     * termina en FAILED con el reporte, decidible vía /decision).
     */
    /**
     * Verificado en vivo (MISSION-SANDBOX-VERIFY-1): en un reintento el líder copió los errores del validador
     * a participationConflicts y la misión se cortó sin corregir. Un conflicto solo es válido para un miembro
     * real, que no sea el líder y que no tenga tarea en el plan; si no, es un error más que se corrige.
     */
    static java.util.List<String> invalidParticipationConflicts(TeamPlan plan, TeamSnapshot team) {
        var members = team.members().stream().map(TeamMemberInfo::agentId).collect(java.util.stream.Collectors.toSet());
        var withTask = plan.tasksOrEmpty().stream()
                .filter(java.util.Objects::nonNull)
                .map(TeamPlan.PlannedTask::agentId)
                .collect(java.util.stream.Collectors.toSet());
        var errors = new ArrayList<String>();
        for (var conflict : plan.participationConflictsOrEmpty()) {
            var agentId = conflict == null ? null : conflict.agentId();
            if (agentId == null || !members.contains(agentId)) {
                errors.add("participationConflicts: \"" + agentId + "\" no es miembro del equipo.");
            } else if (agentId.equals(team.leaderAgentId())) {
                errors.add("participationConflicts: el líder (" + agentId + ") no puede declararse sin trabajo; "
                        + "los errores de validación se corrigen en el plan, no se declaran como conflicto.");
            } else if (withTask.contains(agentId)) {
                errors.add("participationConflicts: " + agentId + " tiene una tarea en el plan; un conflicto solo "
                        + "es para un miembro sin trabajo real. Corrige los errores en el plan y deja "
                        + "participationConflicts vacío.");
            } else {
                ownedLayersInProfile(plan, team, agentId).ifPresent(layers -> errors.add(
                        "participationConflicts: " + agentId + " sí tiene trabajo real: según el reparto fijo de capas "
                                + "es dueño de " + layers + " en " + plan.stackProfile() + ". Asígnale esa tarea y deja "
                                + "participationConflicts vacío."));
            }
        }
        return errors;
    }

    /**
     * Verificado en vivo (MISSION-E2E-ENG): el líder declaró a Mila sin trabajo en una API .NET aunque
     * {@link com.aicompany.core.model.RoleLayerCatalog} le da la capa API. Con perfil de stack, Java sabe qué capas
     * le tocan a cada rol: si el miembro es dueño de alguna del perfil, su conflicto no es legítimo.
     */
    private static java.util.Optional<java.util.List<com.aicompany.core.model.StackProfile.Layer>> ownedLayersInProfile(
            TeamPlan plan, TeamSnapshot team, String agentId) {
        var profile = plan.profile();
        if (profile.isEmpty()) {
            return java.util.Optional.empty();
        }
        var roleCode = team.members().stream().filter(m -> agentId.equals(m.agentId()))
                .map(TeamMemberInfo::roleCode).findFirst().orElse(null);
        var owned = com.aicompany.core.model.RoleLayerCatalog.layersFor(roleCode).orElse(java.util.List.of()).stream()
                .filter(profile.get().layers()::contains)
                .toList();
        return owned.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(owned);
    }

    private TeamPlanResult reportParticipationConflict(
            String missionId, String taskId, String leaderId, String teamId, TeamPlan plan) {

        var conflicts = plan.participationConflictsOrEmpty().stream()
                .filter(java.util.Objects::nonNull)
                .map(c -> c.agentId() + ": " + c.reason())
                .collect(Collectors.joining("\n- ", "- ", ""));

        var message = "El líder " + leaderId + " reporta una incompatibilidad entre el objetivo de la misión y la "
                + "regla de participación de " + teamId + " (no se ejecutó nada):\n" + conflicts;

        memory.updateTask(taskId, "FAILED", message);
        publishRejected(missionId, taskId, leaderId, 0, message);

        throw new IllegalStateException(message);
    }

    private static String quotedList(java.util.List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * El formato del action es cosmético (verificado en vivo: un plan válido
     * se perdía por "DESIGN-ARCHITECTURE"): mayúsculas, sin acentos, y todo
     * lo que no sea letra se vuelve "_". Nunca cambia agentes, capabilities
     * ni rutas, que siguen validándose de forma estricta.
     */
    static TeamPlan normalizeActions(TeamPlan plan) {

        if (plan == null || plan.tasks() == null) {
            return plan;
        }

        var tasks = plan.tasks().stream()
                .map(t -> t == null || t.action() == null ? t : new TeamPlan.PlannedTask(
                        t.agentId(), t.kind(), normalizeAction(t.action()), t.objective(),
                        t.requiredCapabilities(), t.ownedPaths(), t.assignments()))
                .toList();

        return new TeamPlan(plan.summary(), plan.techStack(), plan.entryPoint(), tasks, plan.participationConflicts(),
                plan.stackProfile(), plan.boundedContexts(), plan.ubiquitousLanguage());
    }

    private static String normalizeAction(String action) {
        return java.text.Normalizer.normalize(action, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /**
     * Verificado en vivo (MISSION-DDD-VERIFY-2): sin el plan anterior el modelo regeneraba desde cero y traía
     * errores nuevos en cada intento. Con el plan rechazado a la vista, la corrección es incremental.
     */
    private String correctionBlock(String feedback, String previousPlanJson) {

        var previous = previousPlanJson == null ? "" : """

                PLAN ANTERIOR (rechazado):
                %s

                Toma ESTE plan como base y corrige SOLO los errores indicados; conserva todo lo demás tal cual.
                """.formatted(previousPlanJson);

        return """

                CORRECCIÓN DEL INTENTO ANTERIOR

                El plan anterior fue rechazado por validaciones deterministas.
                Corrige únicamente estos errores:
                %s
                """.formatted(feedback) + previous;
    }

    private String toJson(TeamPlan plan) {
        try {
            return jsonMapper.writeValueAsString(plan);
        } catch (JacksonException ex) {
            throw new IllegalStateException("No se pudo serializar el plan del equipo.", ex);
        }
    }

    private String safeMessage(Exception ex, String defaultMessage) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? defaultMessage : ex.getMessage();
    }
}

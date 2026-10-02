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

    private AgentAvailability agentAvailability;

    /** Decisión del fundador (2026-10-01): el líder solo ve a los miembros encendidos (opcional en tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAgentAvailability(AgentAvailability agentAvailability) {
        this.agentAvailability = agentAvailability;
    }

    private TeamSnapshot onlyEnabledMembers(TeamSnapshot team) {
        if (agentAvailability == null || team == null) {
            return team;
        }
        return new TeamSnapshot(team.teamId(), team.teamName(), team.status(), team.leaderAgentId(),
                team.members().stream().filter(m -> agentAvailability.isEnabled(m.agentId())).toList());
    }

    public TeamPlanResult plan(String missionId, String teamId, String instruction, TeamExecutionMode mode) {
        return plan(missionId, teamId, instruction, mode, 0);
    }

    /** Equipo real (Neo4j) para una ronda de evidencia que reutiliza el plan guardado. */
    public TeamSnapshot teamSnapshot(String teamId) {
        return teamMemory.snapshot(teamId);
    }

    public TeamPlanResult plan(String missionId, String teamId, String instruction, TeamExecutionMode mode, int round) {

        var team = onlyEnabledMembers(teamMemory.snapshot(teamId));

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

                // Revisión final del bloque 1 (I-1): el validador corre siempre y sus errores van primero, para
                // que un error derivado del resolutor (p. ej. capa sin dueño) no oculte la causa real (un perfil o
                // un rol no habilitado, un agente que no escribe código).
                var resolverErrors = new ArrayList<String>();

                if (mode == TeamExecutionMode.DEVELOPMENT) {
                    var resolution = resolver.resolve(plan, team);
                    resolverErrors.addAll(resolution.errors());
                    plan = resolution.plan();
                }

                var errors = new ArrayList<String>(validator.validate(plan, team, mode));
                resolverErrors.stream().filter(error -> !errors.contains(error)).forEach(errors::add);

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
                - Este equipo produce CÓDIGO REAL en un repositorio Git. Elige SOLO los miembros necesarios para este
                  producto: no crees tareas de relleno para nadie. Un agente que no participa simplemente no aparece.
                - Quién escribe qué lo decide Forjai según el rol: %s.
                - Incluye siempre al miembro QA: escribe un test por escenario después del código y al final revisa.
                - ownedPaths y assignments los calcula Forjai: pon kind "WORK", ownedPaths [] y assignments [] en todas
                  las tareas.
                - Metodología obligatoria: DDD.
                - stackProfile: elige EXACTAMENTE uno de estos perfiles del catálogo (no existen otros):
                %s
                - boundedContexts: los bounded contexts del producto, cada uno con name (en el formato del perfil) y
                  description. El name define las rutas de sus capas. Para un producto chico, UN solo contexto.
                - ubiquitousLanguage: al menos 3 términos del dominio, cada uno con term y definition.
                - requiredCapabilities: solo capabilities que figuren en la lista de ESE agente, una por elemento.
                - Reglas de capas: domain no depende de nada fuera de su domain ni de frameworks; application solo de
                  domain; infrastructure/api/presentation/game dependen de application y domain.
                """.formatted(RoleLayerCatalog.describe(), StackProfile.describeAll());
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

        // Verificado en vivo (2026-09-29, glm-5.3): el líder omitía summary en planes por lo demás válidos. Es un campo
        // descriptivo, así que Java lo arma desde las tareas en vez de gastar un intento.
        var summary = plan.summary() == null || plan.summary().isBlank()
                ? tasks.stream().filter(java.util.Objects::nonNull)
                        .map(t -> t.agentId() + ": " + t.action() + (t.objective() == null ? "" : " (" + t.objective() + ")"))
                        .collect(java.util.stream.Collectors.joining("; ", "Plan del equipo (resumen armado por Java): ", "."))
                : plan.summary();

        return new TeamPlan(summary, plan.techStack(), plan.entryPoint(), tasks, plan.participationConflicts(),
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

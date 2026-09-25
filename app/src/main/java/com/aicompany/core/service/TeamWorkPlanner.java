package com.aicompany.core.service;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.validation.TeamPlanValidator;
import com.aicompany.core.event.CompanyEventPublisher;
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

    private final TeamMemoryService teamMemory;
    private final CeoService ceoService;
    private final CompanyMemoryService companyMemory;
    private final PromptMemoryService promptMemory;
    private final MissionMemoryService memory;
    private final CompanyEventPublisher events;
    private final TeamPlanValidator validator;
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
            JsonMapper jsonMapper,
            @Value("${ollama.agent-model}") String defaultAgentModel) {

        this.teamMemory = teamMemory;
        this.ceoService = ceoService;
        this.companyMemory = companyMemory;
        this.promptMemory = promptMemory;
        this.memory = memory;
        this.events = events;
        this.validator = validator;
        this.jsonMapper = jsonMapper;
        this.defaultAgentModel = defaultAgentModel;
    }

    public TeamPlanResult plan(String missionId, String teamId, String instruction, TeamExecutionMode mode) {

        var team = teamMemory.snapshot(teamId);

        if (team == null || !"ACTIVE".equals(team.status())) {
            throw new IllegalStateException("El equipo " + teamId + " no existe o no está ACTIVE en Company Memory.");
        }

        if (team.leaderAgentId() == null || team.members().isEmpty()) {
            throw new IllegalStateException("El equipo " + teamId + " no tiene líder o miembros en Company Memory.");
        }

        var leaderId = team.leaderAgentId();
        var taskId = missionId + "-" + leaderId.toUpperCase(Locale.ROOT) + "-PLAN";

        memory.createTask(taskId, missionId, leaderId, "TEAM_PLANNING", "PLANNING");
        memory.updateTask(taskId, "RUNNING", "El líder está descomponiendo el trabajo.");
        memory.setAgentStatus(leaderId, "WORKING");

        try {

            var model = companyMemory.agentModel(leaderId, defaultAgentModel);
            var leaderPrompt = promptMemory.activePrompt(leaderId);
            var basePrompt = buildPrompt(team, instruction, mode);
            String feedback = null;

            for (int attempt = 0; attempt <= MAX_PLAN_RETRIES; attempt++) {

                var prompt = feedback == null ? basePrompt : basePrompt + correctionBlock(feedback);

                TeamPlan plan;

                try {
                    plan = ceoService.planTeamWork(leaderId, prompt, leaderPrompt, model);
                } catch (Exception ex) {
                    feedback = "- " + safeMessage(ex, "Respuesta no procesable.");
                    publishRejected(missionId, taskId, leaderId, attempt, feedback);
                    continue;
                }

                var errors = validator.validate(plan, team, mode);

                if (errors.isEmpty()) {

                    memory.updateTask(taskId, "COMPLETED", toJson(plan));

                    events.publish("EMPRESA_TEAM_PLAN_CREATED", missionId, taskId, leaderId,
                            Map.of("teamId", teamId, "tasks", plan.tasksOrEmpty().size()));

                    log.info("MISSION {} - team plan accepted team={} tasks={}",
                            missionId, teamId, plan.tasksOrEmpty().size());

                    return new TeamPlanResult(team, plan);
                }

                feedback = "- " + String.join("\n- ", errors);
                publishRejected(missionId, taskId, leaderId, attempt, feedback);
            }

            var message = "El líder " + leaderId + " no produjo un plan válido para " + teamId
                    + " después de " + (MAX_PLAN_RETRIES + 1) + " intentos:\n" + feedback;

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
                        + " | roleCode=" + m.roleCode() + " | capabilities=" + m.capabilities())
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
                - requiredCapabilities: copia TEXTUALMENTE capabilities de la lista del agente asignado; nunca inventes una.
                - action: identificador corto en MAYÚSCULAS_CON_GUIONES_BAJOS.
                - objective: qué debe entregar ese agente, concreto y verificable.
                """.formatted(leaderName, team.teamName(), team.teamId(), instruction, roster);

        if (mode == TeamExecutionMode.ANALYSIS) {
            return common + """
                    - kind: siempre "WORK" (este equipo no tiene tareas de validación).
                    - techStack y entryPoint: déjalos como "" y ownedPaths como [].
                    """;
        }

        return common + """
                - Este equipo produce CÓDIGO REAL en un repositorio Git: todos los miembros deben recibir exactamente una tarea.
                - Exactamente una tarea kind="VALIDATION", asignada al miembro que tenga la capability "QA":
                  revisará el código sin ejecutarlo. Esa tarea lleva ownedPaths [].
                - Las demás tareas son kind="WORK" y declaran ownedPaths: rutas relativas (carpetas o archivos) que solo
                  ese agente puede escribir. Los ownedPaths de agentes distintos no pueden solaparse.
                  Nunca uses rutas absolutas, "..", "\\" ni ".git".
                - techStack: la tecnología elegida; debe permitir un MVP pequeño y completo con la capacidad real del equipo.
                - entryPoint: ruta relativa del punto de entrada del proyecto; debe caer dentro de los ownedPaths de una tarea WORK.
                """;
    }

    private String correctionBlock(String feedback) {
        return """

                CORRECCIÓN DEL INTENTO ANTERIOR

                El plan anterior fue rechazado por validaciones deterministas.
                Corrige únicamente estos errores:
                %s
                """.formatted(feedback);
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

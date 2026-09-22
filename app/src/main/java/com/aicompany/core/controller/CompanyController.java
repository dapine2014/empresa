package com.aicompany.core.controller;

import com.aicompany.core.model.ActivityItem;
import com.aicompany.core.model.AgentModelCommand;
import com.aicompany.core.model.AgentModelResponse;
import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.ChatRequest;
import com.aicompany.core.model.ChatResponse;
import com.aicompany.core.model.PolicyCommand;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.PolicySnapshot;
import com.aicompany.core.model.PromptCommand;
import com.aicompany.core.model.PromptSnapshot;
import com.aicompany.core.model.PromptVersionContent;
import com.aicompany.core.model.SettingsCommand;
import com.aicompany.core.model.SettingsResponse;
import com.aicompany.core.model.TeamSnapshot;
import com.aicompany.core.service.ActivityMemoryService;
import com.aicompany.core.service.ChatIntentRouter;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.CompanyPolicyService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.PromptMemoryService;
import com.aicompany.core.service.TeamMemoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/company")
public class CompanyController {
    private final CompanyMemoryService memoryService;
    private final MissionMemoryService missionMemoryService;
    private final ActivityMemoryService activityMemoryService;
    private final ChatIntentRouter chatIntentRouter;
    private final TeamMemoryService teamMemoryService;
    private final PromptMemoryService promptMemoryService;
    private final CompanyPolicyService companyPolicyService;

    public CompanyController(
            CompanyMemoryService memoryService,
            MissionMemoryService missionMemoryService,
            ActivityMemoryService activityMemoryService,
            ChatIntentRouter chatIntentRouter,
            TeamMemoryService teamMemoryService,
            PromptMemoryService promptMemoryService,
            CompanyPolicyService companyPolicyService) {

        this.memoryService = memoryService;
        this.missionMemoryService = missionMemoryService;
        this.activityMemoryService = activityMemoryService;
        this.chatIntentRouter = chatIntentRouter;
        this.teamMemoryService = teamMemoryService;
        this.promptMemoryService = promptMemoryService;
        this.companyPolicyService = companyPolicyService;
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        return memoryService.agents();
    }

    /**
     * Estado real (última {@code AgentTask}, no aspiracional) de cada
     * agente — panel "Agents" del Command Center web.
     */
    @GetMapping("/agents/status")
    public List<AgentStatusResponse> agentStatus() {
        return missionMemoryService.latestTaskPerAgent();
    }

    /**
     * Cambia el modelo LLM real de un agente puntual — toma efecto en su
     * próxima tarea/llamada, sin caché ni reinicio (ver
     * {@code AgentRuntime}/{@code MissionExecutor}/{@code ChatIntentRouter},
     * que resuelven el modelo real desde Company Memory en cada llamada).
     * Sin validar contra qué modelos existen en Ollama — mismo criterio
     * que el resto del proyecto con `ollama.ceo-model`/`ollama.agent-model`.
     */
    @PutMapping("/agents/{id}/model")
    public AgentModelResponse updateAgentModel(
            @PathVariable("id") String id,
            @Valid @RequestBody AgentModelCommand command) {

        memoryService.setAgentModel(id, command.model());

        return new AgentModelResponse(id, command.model());
    }

    /**
     * Los 3 equipos reales (Engineering, Creative/Product Intelligence,
     * Marketing & Growth) con sus miembros y líder — panel "Agents" del
     * Command Center web (vista de organigrama). Estructura únicamente:
     * el estado real/tarea actual de cada agente sigue viniendo de
     * {@link #agentStatus()}, nunca duplicado acá.
     */
    @GetMapping("/teams")
    public List<TeamSnapshot> teams() {
        return teamMemoryService.snapshotAll();
    }

    /**
     * Prompt versionado de un agente puntual — panel "Agents" del
     * Command Center web (editor dentro del organigrama). Versión
     * activa + historial (sin el contenido de cada versión vieja, ver
     * {@code GET .../versions/{version}}).
     */
    @GetMapping("/agents/{id}/prompt")
    public PromptSnapshot agentPrompt(@PathVariable("id") String id) {
        return promptMemoryService.snapshot(id);
    }

    /**
     * Contenido completo de una versión puntual del historial — para
     * previsualizar antes de activarla (rollback).
     */
    @GetMapping("/agents/{id}/prompt/versions/{version}")
    public PromptVersionContent agentPromptVersion(
            @PathVariable("id") String id,
            @PathVariable("version") int version) {

        return new PromptVersionContent(version, promptMemoryService.versionContent(id, version));
    }

    /**
     * Crea una versión nueva del prompt de este agente y la activa —
     * nunca pisa una versión existente. Toma efecto en la próxima
     * llamada real a Ollama de este agente, sin caché ni reinicio
     * (mismo criterio ya usado para {@code PUT /agents/{id}/model}).
     */
    @PutMapping("/agents/{id}/prompt")
    public PromptSnapshot updateAgentPrompt(
            @PathVariable("id") String id,
            @Valid @RequestBody PromptCommand command) {

        return promptMemoryService.createVersion(id, command.content(), command.changeReason());
    }

    /**
     * Rollback: reactiva una versión existente del historial — nunca
     * crea contenido nuevo.
     */
    @PutMapping("/agents/{id}/prompt/versions/{version}/activate")
    public PromptSnapshot activateAgentPromptVersion(
            @PathVariable("id") String id,
            @PathVariable("version") int version) {

        return promptMemoryService.activateVersion(id, version);
    }

    /**
     * Las 7 Company Financial Policies vigentes -- panel "Settings" del
     * Command Center web, sección "Financial Policies".
     */
    @GetMapping("/policies")
    public List<PolicySnapshot> policies() {
        return companyPolicyService.snapshotAll();
    }

    /**
     * Crea una versión nueva de una política y la activa de inmediato --
     * a diferencia del prompt de agentes, acá no hay borrador.
     */
    @PutMapping("/policies/{key}")
    public PolicySnapshot updatePolicy(
            @PathVariable("key") String key,
            @Valid @RequestBody PolicyCommand command) {

        return companyPolicyService.createVersion(PolicyKey.valueOf(key), command.value(), command.changeReason());
    }

    /** Rollback: reactiva una versión existente del historial. */
    @PutMapping("/policies/{key}/versions/{version}/activate")
    public PolicySnapshot activatePolicyVersion(
            @PathVariable("key") String key,
            @PathVariable("version") int version) {

        return companyPolicyService.activateVersion(PolicyKey.valueOf(key), version);
    }

    /**
     * Línea de tiempo reciente derivada de Neo4j — panel "Activity" del
     * Command Center web. Ver {@code ActivityMemoryService} para por qué
     * no es un consumer de Kafka.
     */
    @GetMapping("/activity")
    public List<ActivityItem> activity(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {

        return activityMemoryService.recent(limit);
    }

    /**
     * Command Center web: el chat no ejecuta directamente nada por sí
     * mismo — {@link ChatIntentRouter} decide si es inicio de misión,
     * una decisión real de gobernanza, una consulta con datos reales, o
     * chat general.
     */
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return new ChatResponse("CEO", chatIntentRouter.route(request.message()));
    }

    /**
     * Correo de alertas (a quién le llegan, `empresa.md` §18) y correo
     * propio del sistema (remitente) — panel "Settings" del Command
     * Center web. La clave del correo del sistema nunca se devuelve.
     */
    @GetMapping("/settings")
    public SettingsResponse settings() {
        return new SettingsResponse(memoryService.alertEmail(), memoryService.systemEmail());
    }

    /**
     * {@code systemEmail}/{@code mailPassword} son opcionales: solo se
     * actualizan si vienen no vacíos, para no obligar a retipear la clave
     * del sistema cada vez que se cambia el correo de alertas.
     */
    @PutMapping("/settings")
    public SettingsResponse updateSettings(@Valid @RequestBody SettingsCommand command) {
        memoryService.setAlertEmail(command.alertEmail());

        if (command.systemEmail() != null && !command.systemEmail().isBlank()) {
            memoryService.setSystemEmail(command.systemEmail());
        }

        if (command.mailPassword() != null && !command.mailPassword().isBlank()) {
            memoryService.setMailPassword(command.mailPassword());
        }

        return new SettingsResponse(command.alertEmail(), memoryService.systemEmail());
    }
}

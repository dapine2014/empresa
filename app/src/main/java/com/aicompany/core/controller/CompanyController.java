package com.aicompany.core.controller;

import com.aicompany.core.model.ActivityItem;
import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.ChatRequest;
import com.aicompany.core.model.ChatResponse;
import com.aicompany.core.model.SettingsCommand;
import com.aicompany.core.model.SettingsResponse;
import com.aicompany.core.service.ActivityMemoryService;
import com.aicompany.core.service.ChatIntentRouter;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
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

    public CompanyController(
            CompanyMemoryService memoryService,
            MissionMemoryService missionMemoryService,
            ActivityMemoryService activityMemoryService,
            ChatIntentRouter chatIntentRouter) {

        this.memoryService = memoryService;
        this.missionMemoryService = missionMemoryService;
        this.activityMemoryService = activityMemoryService;
        this.chatIntentRouter = chatIntentRouter;
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
     * Correo al que se envían las alertas inmediatas (`empresa.md` §18) —
     * panel "Settings" del Command Center web.
     */
    @GetMapping("/settings")
    public SettingsResponse settings() {
        return new SettingsResponse(memoryService.alertEmail());
    }

    @PutMapping("/settings")
    public SettingsResponse updateSettings(@Valid @RequestBody SettingsCommand command) {
        memoryService.setAlertEmail(command.alertEmail());
        return new SettingsResponse(command.alertEmail());
    }
}

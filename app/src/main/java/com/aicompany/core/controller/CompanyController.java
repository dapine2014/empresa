package com.aicompany.core.controller;

import com.aicompany.core.model.ChatRequest;
import com.aicompany.core.model.ChatResponse;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/company")
public class CompanyController {
    private final CeoService ceoService;
    private final CompanyMemoryService memoryService;
    private final MissionService missionService;

    public CompanyController(CeoService ceoService, CompanyMemoryService memoryService, MissionService missionService) {
        this.ceoService = ceoService;
        this.memoryService = memoryService;
        this.missionService = missionService;
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> agents() {
        return memoryService.agents();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        var message = request.message();
        var matcher = Pattern.compile("(?i)\\b(?:ejecuta|inicia)\\s+(MISSION-\\d+)\\b").matcher(message);
        if (matcher.find()) {
            var missionId = matcher.group(1).toUpperCase();
            var response = missionService.start(missionId, message);
            return new ChatResponse("CEO", "He recibido " + missionId + ". Estado: " + response.status() + ". La misión está procesándose en segundo plano. Consulta /api/company/missions/" + missionId + "/details para ver el progreso y las tareas.");
        }
        return new ChatResponse("CEO", ceoService.chat(message));
    }
}

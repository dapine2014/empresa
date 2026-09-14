package com.aicompany.core.controller;

import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.DecisionResponse;
import com.aicompany.core.model.MissionCommand;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatusResponse;
import com.aicompany.core.service.MissionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/company/missions")
public class MissionController {
    private final MissionService missionService;

    public MissionController(MissionService missionService) {
        this.missionService = missionService;
    }

    @PostMapping
    public ResponseEntity<MissionResponse> start(@Valid @RequestBody MissionCommand command) {
        return ResponseEntity.accepted().body(missionService.start(command.missionId(), command.instruction(), command.environmentOrDefault()));
    }

    /**
     * Misiones recientes para el panel "Missions" del Command Center web.
     */
    @GetMapping
    public List<MissionResponse> list() {
        return missionService.list();
    }

    @GetMapping("/{missionId}")
    public ResponseEntity<MissionResponse> status(@PathVariable String missionId) {
        return missionService.status(missionId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{missionId}/details")
    public ResponseEntity<MissionStatusResponse> details(@PathVariable String missionId) {
        return missionService.details(missionId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Decisión real del fundador humano sobre una misión en
     * {@code AWAITING_INVESTOR} (o {@code FAILED}) — nunca generada por un
     * agente ni por el CEO.
     */
    @PostMapping("/{missionId}/decision")
    public ResponseEntity<DecisionResponse> decide(
            @PathVariable String missionId,
            @Valid @RequestBody DecisionCommand command) {

        return missionService.recordDecision(missionId, command)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

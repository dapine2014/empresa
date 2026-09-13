package com.aicompany.core.controller;

import com.aicompany.core.model.MissionCommand;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatusResponse;
import com.aicompany.core.service.MissionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/company/missions")
public class MissionController {
    private final MissionService missionService;

    public MissionController(MissionService missionService) {
        this.missionService = missionService;
    }

    @PostMapping
    public ResponseEntity<MissionResponse> start(@Valid @RequestBody MissionCommand command) {
        return ResponseEntity.accepted().body(missionService.start(command.missionId(), command.instruction()));
    }

    @GetMapping("/{missionId}")
    public ResponseEntity<MissionResponse> status(@PathVariable String missionId) {
        return missionService.status(missionId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{missionId}/details")
    public ResponseEntity<MissionStatusResponse> details(@PathVariable String missionId) {
        return missionService.details(missionId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}

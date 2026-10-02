package com.aicompany.core.database;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Base de datos de una misión (spec 2026-10-02 §3 y §4): estado y "Aplicar esquema". */
@RestController
@RequestMapping("/api/company/missions/{id}/database")
public class MissionDatabaseController {

    private final DatabaseApplyService service;

    public MissionDatabaseController(DatabaseApplyService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<DatabaseApplyService.MissionDatabaseStatus> status(@PathVariable("id") String id) {
        return service.status(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/apply")
    public Map<String, String> apply(@PathVariable("id") String id) {
        return Map.of("result", service.applyLatest(id));
    }
}

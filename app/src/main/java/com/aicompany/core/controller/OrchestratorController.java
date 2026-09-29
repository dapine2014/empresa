package com.aicompany.core.controller;

import com.aicompany.core.service.ProductOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orquestador del ciclo de producto (spec 2026-09-28 §4): el ciclo en curso (o el último) con su historial de pasos.
 * Pausar y reanudar se hace con la policy {@code ORCHESTRATOR_ENABLED} (Settings o chat).
 */
@RestController
@RequestMapping("/api/company/orchestrator")
public class OrchestratorController {

    private final ProductOrchestrator orchestrator;

    public OrchestratorController(ProductOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public ProductOrchestrator.OrchestratorView current() {
        return orchestrator.current();
    }
}

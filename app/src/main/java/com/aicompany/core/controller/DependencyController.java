package com.aicompany.core.controller;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DependencyRef;
import com.aicompany.core.service.DependencyMemoryService;
import com.aicompany.core.service.SandboxRunnerClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Dependencias gobernadas (spec §3): el fundador aprueba (🔴) o rechaza lo que la política dejó pendiente. Aprobar
 * promueve el paquete desde el staging del job que lo descargó; si eso falla, el estado no cambia.
 */
@RestController
@RequestMapping("/api/company/dependencies")
public class DependencyController {

    private final DependencyMemoryService memory;
    private final SandboxRunnerClient runner;
    private final CompanyEventPublisher events;

    public DependencyController(DependencyMemoryService memory, SandboxRunnerClient runner, CompanyEventPublisher events) {
        this.memory = memory;
        this.runner = runner;
        this.events = events;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return memory.list();
    }

    @PutMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String id) {
        var dependency = memory.find(id);
        if (dependency.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var d = dependency.get();
        var ref = new DependencyRef(String.valueOf(d.get("ecosystem")), String.valueOf(d.get("name")),
                String.valueOf(d.get("version")));
        if (!runner.promoteDependencies(ref.ecosystem(), String.valueOf(d.get("jobId")), List.of(ref))) {
            throw new IllegalStateException("No se pudo promover " + id + ": " + runner.lastError());
        }
        memory.decide(id, "APPROVED", "founder");
        events.publish("EMPRESA_DEPENDENCY_APPROVED", (String) d.get("missionId"), null, "human",
                Map.of("dependency", id, "approvedBy", "founder"));
        return ResponseEntity.ok(Map.of("id", id, "status", "APPROVED"));
    }

    @PutMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable String id) {
        var dependency = memory.find(id);
        if (dependency.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        memory.decide(id, "REJECTED", "founder");
        events.publish("EMPRESA_DEPENDENCY_REJECTED", (String) dependency.get().get("missionId"), null, "human",
                Map.of("dependency", id));
        return ResponseEntity.ok(Map.of("id", id, "status", "REJECTED"));
    }
}

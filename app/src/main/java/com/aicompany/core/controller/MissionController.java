package com.aicompany.core.controller;

import com.aicompany.core.model.InteractionDesignView;
import com.aicompany.core.model.RequirementsDecisionCommand;
import com.aicompany.core.model.RequirementsView;
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

    private final com.aicompany.core.recovery.MissionRecoveryService recoveryService;

    public MissionController(MissionService missionService,
                             com.aicompany.core.recovery.MissionRecoveryService recoveryService) {
        this.missionService = missionService;
        this.recoveryService = recoveryService;
    }

    /** Spec misiones durables §1: retoma una misión INTERRUPTED del Development Group. */
    @PostMapping("/{missionId}/resume")
    public ResponseEntity<MissionResponse> resume(@PathVariable String missionId) {
        recoveryService.resume(missionId);
        return missionService.status(missionId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Spec misiones durables, revisión 2026-10-06 §5 (manos humanas): verifica el último commit del repositorio sin
     * modelos (un desarrollador corrigió e hizo commit).
     */
    @PostMapping("/{missionId}/verify")
    public ResponseEntity<MissionResponse> verify(@PathVariable String missionId) {
        recoveryService.verify(missionId);
        return missionService.status(missionId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<MissionResponse> start(@Valid @RequestBody MissionCommand command) {
        return ResponseEntity.accepted().body(
                missionService.start(command.missionId(), command.instruction(), command.environmentOrDefault(),
                        command.financialCriteria(), command.teamIdOrNull(), command.databasesOrEmpty())
        );
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
     * Borrado real de una misión terminada (limpieza de misiones de
     * prueba). Si está en curso, es la fundacional o tiene clientes/ventas
     * reales, {@code MissionService} lanza {@code IllegalStateException}
     * → 500, misma convención que el resto de controllers.
     */
    @DeleteMapping("/{missionId}")
    public ResponseEntity<Void> delete(@PathVariable String missionId) {
        return missionService.delete(missionId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
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

    /** Spec 2026-10-03 Aria §2: requisitos de Aria de la misión (versión vigente y aprobada). */
    @GetMapping("/{missionId}/requirements")
    public ResponseEntity<RequirementsView> requirements(@PathVariable String missionId) {
        return missionService.requirements(missionId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Spec 2026-10-14 Kael §3: diseño de Kael de la misión (404 = sin diseño). */
    @GetMapping("/{missionId}/interaction-design")
    public ResponseEntity<InteractionDesignView> interactionDesign(@PathVariable String missionId) {
        return missionService.interactionDesign(missionId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{missionId}/requirements/decision")
    public RequirementsView decideRequirements(@PathVariable String missionId,
                                               @RequestBody RequirementsDecisionCommand command) {
        return missionService.decideRequirements(missionId, command);
    }

    /** Spec 2026-10-03 Aria §3: construir el incremento N (ronda de la misma misión). */
    @PostMapping("/{missionId}/increments/{increment}")
    public RequirementsView buildIncrement(@PathVariable String missionId, @PathVariable int increment) {
        return missionService.buildIncrement(missionId, increment);
    }

    private com.aicompany.core.service.ModelCostService modelCost;

    /** Costo real por misión (2026-10-04). Opcional en tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelCost(com.aicompany.core.service.ModelCostService modelCost) {
        this.modelCost = modelCost;
    }

    public record ModelCostView(String missionId, double totalUsd, long calls,
                                java.util.List<com.aicompany.core.service.ModelUsageMemoryService.Row> rows) {
    }

    /** Costo real por misión (2026-10-04): tokens y US$ de las llamadas remotas de la misión (NVIDIA = US$ 0). */
    @GetMapping("/{missionId}/model-cost")
    public ModelCostView modelCost(@PathVariable String missionId) {
        var summary = modelCost == null ? new com.aicompany.core.service.ModelCostService.Summary(java.util.List.of())
                : modelCost.missionCost(missionId);
        return new ModelCostView(missionId, summary.totalUsd(), summary.calls(), summary.rows());
    }
}

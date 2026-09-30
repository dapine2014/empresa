package com.aicompany.core.service;

import com.aicompany.core.model.PolicyKey;
import org.springframework.stereotype.Service;

/**
 * Spec modo automático (2026-09-29): los interruptores son la policy versionada {@code ORCHESTRATOR_ENABLED} (una sola
 * fuente de verdad con Settings y el chat). "Buscar clientes" queda como próximamente hasta la entrega B.
 */
@Service
public class AutonomyService {

    public record Front(boolean enabled, boolean available, String pauseReason) {
    }

    public record Waiting(int orchestratorMissions, int pendingDependencies) {
    }

    public record AutonomyView(Front products, Front clients, Waiting waiting) {
    }

    /** Campos null = no se tocan. {@code clients} está reservado para la búsqueda de prospectos. */
    public record AutonomyCommand(Boolean products, Boolean clients) {
    }

    private final CompanyPolicyService policies;
    private final MissionMemoryService missionMemory;
    private final DependencyMemoryService dependencies;

    public AutonomyService(CompanyPolicyService policies, MissionMemoryService missionMemory,
                           DependencyMemoryService dependencies) {
        this.policies = policies;
        this.missionMemory = missionMemory;
        this.dependencies = dependencies;
    }

    public AutonomyView view() {
        var on = productsOn();
        String reason = null;
        if (!on) {
            var policy = policies.snapshot(PolicyKey.ORCHESTRATOR_ENABLED);
            reason = policy == null ? null : policy.changeReason();
        }
        var pending = (int) dependencies.list().stream().filter(d -> "PENDING_APPROVAL".equals(d.get("status"))).count();
        return new AutonomyView(new Front(on, true, reason), new Front(false, false, null),
                new Waiting(missionMemory.countAwaitingLaunchedBy("orchestrator"), pending));
    }

    /** Devuelve true si cambió; sin cambio no crea versión (el historial no se llena de ruido). */
    public boolean setProducts(boolean on, String origin) {
        if (productsOn() == on) {
            return false;
        }
        policies.createVersion(PolicyKey.ORCHESTRATOR_ENABLED, on ? 1 : 0,
                (on ? "Encendido" : "Apagado") + " desde " + origin);
        return true;
    }

    public AutonomyView update(AutonomyCommand command, String origin) {
        if (command.clients() != null) {
            throw new IllegalArgumentException("La búsqueda de clientes todavía no existe (próximamente).");
        }
        if (command.products() != null) {
            setProducts(command.products(), origin);
        }
        return view();
    }

    private boolean productsOn() {
        return policies.activeValue(PolicyKey.ORCHESTRATOR_ENABLED) >= 1;
    }
}

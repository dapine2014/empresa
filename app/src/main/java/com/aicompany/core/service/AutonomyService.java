package com.aicompany.core.service;

import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.prospecting.ProspectingMemoryService;
import org.springframework.stereotype.Service;

/**
 * Spec modo automático (2026-09-29): los interruptores son policies versionadas (una sola fuente de verdad con Settings
 * y el chat): "Crear productos y servicios" = {@code ORCHESTRATOR_ENABLED}, "Buscar clientes" = {@code
 * PROSPECTING_ENABLED} (spec búsqueda de prospectos, 2026-09-30).
 */
@Service
public class AutonomyService {

    public record Front(boolean enabled, boolean available, String pauseReason) {
    }

    public record Waiting(int orchestratorMissions, int pendingDependencies, int pendingStrategies, int pendingDrafts) {
    }

    public record AutonomyView(Front products, Front clients, Waiting waiting) {
    }

    /** Campos null = no se tocan. */
    public record AutonomyCommand(Boolean products, Boolean clients) {
    }

    private final CompanyPolicyService policies;
    private final MissionMemoryService missionMemory;
    private final DependencyMemoryService dependencies;
    private final ProspectingMemoryService prospectingMemory;
    private final com.aicompany.core.outreach.OutreachMemoryService outreachMemory;

    public AutonomyService(CompanyPolicyService policies, MissionMemoryService missionMemory,
                           DependencyMemoryService dependencies, ProspectingMemoryService prospectingMemory,
                           com.aicompany.core.outreach.OutreachMemoryService outreachMemory) {
        this.policies = policies;
        this.missionMemory = missionMemory;
        this.dependencies = dependencies;
        this.prospectingMemory = prospectingMemory;
        this.outreachMemory = outreachMemory;
    }

    public AutonomyView view() {
        var pending = (int) dependencies.list().stream().filter(d -> "PENDING_APPROVAL".equals(d.get("status"))).count();
        return new AutonomyView(front(PolicyKey.ORCHESTRATOR_ENABLED), front(PolicyKey.PROSPECTING_ENABLED),
                new Waiting(missionMemory.countAwaitingLaunchedBy("orchestrator"), pending,
                        prospectingMemory.pendingStrategies(), outreachMemory.drafts("PENDING_APPROVAL").size()));
    }

    private Front front(PolicyKey key) {
        var on = policies.activeValue(key) >= 1;
        String reason = null;
        if (!on) {
            var policy = policies.snapshot(key);
            reason = policy == null ? null : policy.changeReason();
        }
        return new Front(on, true, reason);
    }

    /** Devuelve true si cambió; sin cambio no crea versión (el historial no se llena de ruido). */
    public boolean setProducts(boolean on, String origin) {
        return set(PolicyKey.ORCHESTRATOR_ENABLED, on, origin);
    }

    public boolean setClients(boolean on, String origin) {
        return set(PolicyKey.PROSPECTING_ENABLED, on, origin);
    }

    private boolean set(PolicyKey key, boolean on, String origin) {
        if ((policies.activeValue(key) >= 1) == on) {
            return false;
        }
        policies.createVersion(key, on ? 1 : 0, (on ? "Encendido" : "Apagado") + " desde " + origin);
        return true;
    }

    public AutonomyView update(AutonomyCommand command, String origin) {
        if (command.products() != null) {
            setProducts(command.products(), origin);
        }
        if (command.clients() != null) {
            setClients(command.clients(), origin);
        }
        return view();
    }
}

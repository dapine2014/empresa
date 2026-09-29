package com.aicompany.core.model;

/** Paso del ciclo del orquestador (spec 2026-09-28). Activos: los que todavía avanzan. */
public enum OrchestratorStatus {
    CHOOSING, DISCOVERING, PROPOSING, BUILDING, READY, FAILED, STOPPED;

    public boolean active() {
        return this == CHOOSING || this == DISCOVERING || this == PROPOSING || this == BUILDING;
    }
}

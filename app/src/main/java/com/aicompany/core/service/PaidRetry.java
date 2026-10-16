package com.aicompany.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Decisión del fundador (2026-10-16, MISSION-PONG-NIVELES-1792188926): cuando un ciclo de reintentos por respuesta
 * inválida agota sus intentos con el modelo del agente, se hace UN intento más con su respaldo pago
 * ({@code Agent.paidFallbackModel}). Solo si el respaldo existe, es otro modelo, el usado no estaba DOWN al empezar
 * (entonces CeoService ya mandó los intentos al respaldo) y el respaldo no está DOWN. Nunca tras una cancelación.
 * Decisión determinista en Java; cada ciclo arranca un tracker con {@link #start} y le pide {@link #next} al agotar.
 */
public final class PaidRetry {

    private static final Logger log = LoggerFactory.getLogger(PaidRetry.class);

    private final String agentId;
    private final String usedModel;
    private final boolean usedWasDown;
    private final CompanyMemoryService company;
    private final ModelHealthService health;
    private boolean cancelled;
    private boolean decided;
    private String used;

    private PaidRetry(String agentId, String usedModel, boolean usedWasDown, CompanyMemoryService company,
                      ModelHealthService health) {
        this.agentId = agentId;
        this.usedModel = usedModel;
        this.usedWasDown = usedWasDown;
        this.company = company;
        this.health = health;
    }

    /** El respaldo pago para el intento extra, o vacío. Sin ModelHealthService no se mira DOWN (el usado se asume UP). */
    public static Optional<String> modelFor(String agentId, String usedModel, boolean usedWasDown,
                                            CompanyMemoryService company, ModelHealthService health) {
        if (usedWasDown || company == null) {
            return Optional.empty();
        }
        var paid = company.agentPaidFallbackModel(agentId);
        if (paid == null || paid.isBlank()) {
            return Optional.empty();
        }
        paid = paid.strip();
        if (paid.equals(usedModel == null ? null : usedModel.strip())) {
            return Optional.empty();
        }
        if (health != null && health.isDown(paid)) {
            return Optional.empty();
        }
        return Optional.of(paid);
    }

    /** Arranca el tracker al comenzar el ciclo: recuerda si el modelo usado ya estaba DOWN. */
    public static PaidRetry start(String agentId, String usedModel, CompanyMemoryService company,
                                  ModelHealthService health) {
        var down = health != null && usedModel != null && health.isDown(usedModel);
        return new PaidRetry(agentId, usedModel, down, company, health);
    }

    /** Anota el error de un intento: una cancelación de misión apaga el intento extra. */
    public void note(Throwable error) {
        if (error instanceof MissionCancellations.MissionCancelledException) {
            cancelled = true;
        }
    }

    /** Al agotar los intentos: el modelo del intento extra (una sola vez por ciclo) o vacío. */
    public Optional<String> next(String step) {
        if (decided || cancelled) {
            return Optional.empty();
        }
        decided = true;
        var paid = modelFor(agentId, usedModel, usedWasDown, company, health);
        paid.ifPresent(model -> {
            used = model;
            log.warn("PAID_RETRY agent={} step={} model={} after={}", agentId, step, model, usedModel);
        });
        return paid;
    }

    /** El respaldo pago con el que se hizo el intento extra, o null. */
    public String used() {
        return used;
    }

    /** Para el mensaje final: «… después de N intentos (y uno con el respaldo pago X)». */
    public String suffix() {
        return used == null ? "" : " (y uno con el respaldo pago " + used + ")";
    }
}

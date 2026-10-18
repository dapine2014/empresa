package com.aicompany.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Decisión del fundador (2026-10-16, MISSION-PONG-NIVELES-1792188926): cuando un ciclo de reintentos por respuesta
 * inválida agota sus intentos con el modelo del agente, se hace UN intento más con su respaldo pago
 * ({@code Agent.paidFallbackModel}). Solo si el respaldo existe, los intentos no corrieron ya en él (el modelo que
 * los atendió, {@code ModelHealthService.effectiveModel}, es otro: el del agente o uno de la cadena gratis) y el
 * respaldo no está DOWN. Nunca tras una cancelación.
 * Decisión determinista en Java; cada ciclo arranca un tracker con {@link #start} y le pide {@link #next} al agotar.
 */
public final class PaidRetry {

    private static final Logger log = LoggerFactory.getLogger(PaidRetry.class);

    private final String agentId;
    private final String effectiveModel;
    private final CompanyMemoryService company;
    private final ModelHealthService health;
    private boolean cancelled;
    private boolean decided;
    private String used;

    private PaidRetry(String agentId, String effectiveModel, CompanyMemoryService company, ModelHealthService health) {
        this.agentId = agentId;
        this.effectiveModel = effectiveModel;
        this.company = company;
        this.health = health;
    }

    /**
     * El respaldo pago para el intento extra, o vacío. effectiveModel es quien atendió los intentos
     * (ModelHealthService.effectiveModel): si ya era el respaldo pago, no se repite. Sin ModelHealthService no se mira
     * si el respaldo está DOWN.
     */
    public static Optional<String> modelFor(String agentId, String effectiveModel, CompanyMemoryService company,
                                            ModelHealthService health) {
        if (company == null) {
            return Optional.empty();
        }
        var paid = company.agentPaidFallbackModel(agentId);
        if (paid == null || paid.isBlank()) {
            return Optional.empty();
        }
        paid = paid.strip();
        if (paid.equals(effectiveModel == null ? null : effectiveModel.strip())) {
            return Optional.empty();
        }
        if (health != null && health.isDown(paid)) {
            return Optional.empty();
        }
        return Optional.of(paid);
    }

    /**
     * Arranca el tracker al comenzar el ciclo: recuerda quién atiende los intentos. Con el modelo del agente caído
     * CeoService los manda a la cadena gratis y después al respaldo pago (verificado en vivo, MISSION-PONG-NIVELES-
     * 1792193906: kimi-k2.6 caído, los atendió nemotron-3-super y el intento pago igual corresponde).
     */
    public static PaidRetry start(String agentId, String usedModel, CompanyMemoryService company,
                                  ModelHealthService health) {
        var effective = health == null || usedModel == null ? usedModel : health.effectiveModel(agentId, usedModel);
        return new PaidRetry(agentId, effective == null ? usedModel : effective, company, health);
    }

    /** Anota el error de un intento: una cancelación de misión (aunque venga envuelta) apaga el intento extra. */
    public void note(Throwable error) {
        if (MissionCancellations.cancellationIn(error).isPresent()) {
            cancelled = true;
        }
    }

    /** Al agotar los intentos: el modelo del intento extra (una sola vez por ciclo) o vacío. */
    public Optional<String> next(String step) {
        if (decided || cancelled) {
            return Optional.empty();
        }
        decided = true;
        var paid = modelFor(agentId, effectiveModel, company, health);
        paid.ifPresent(model -> {
            used = model;
            log.warn("PAID_RETRY agent={} step={} model={} after={}", agentId, step, model, effectiveModel);
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

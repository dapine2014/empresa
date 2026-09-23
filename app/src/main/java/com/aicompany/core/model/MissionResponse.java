package com.aicompany.core.model;

import java.time.Instant;

/**
 * {@code environment} ({@code PRODUCTION}/{@code TEST}) es una propiedad
 * real y separada de {@code status} — antes no existía, y las consultas
 * de negocio del chat (p. ej. "¿qué necesita mi aprobación?") mezclaban
 * misiones de desarrollo/prueba con actividad empresarial real.
 */
public record MissionResponse(
        String missionId,
        MissionStatus status,
        String environment,
        int progress,
        String currentStep,
        String message,
        Instant updatedAt,
        FinancialCriteriaResponse financialCriteria
) {}

package com.aicompany.core.model;

import java.time.Instant;

public record LeadResponse(
        String id,
        String name,
        String description,
        String source,
        String sourceType,
        String missionId,
        String opportunityId,
        Instant createdAt,
        String status,
        String discardReason,
        Instant discardedAt,
        double confidence,
        String contactEmail,
        String contactEmailSource
) {

    /**
     * Constructor de compatibilidad de 12 args (sin datos de contacto,
     * ambos quedan {@code null}) -- evita tocar los ~14 call-sites de
     * test que ya construyen {@code LeadResponse} con la firma
     * anterior.
     */
    public LeadResponse(
            String id,
            String name,
            String description,
            String source,
            String sourceType,
            String missionId,
            String opportunityId,
            Instant createdAt,
            String status,
            String discardReason,
            Instant discardedAt,
            double confidence) {

        this(
                id, name, description, source, sourceType,
                missionId, opportunityId, createdAt, status,
                discardReason, discardedAt, confidence,
                null, null
        );
    }
}

package com.aicompany.core.model;

import java.time.Instant;

/**
 * Fila cruda de finanzas (spec 2026-09-27): kind SALE|EXPENSE|CORRECTION. En EXPENSE, costUsd es el monto del gasto;
 * en CORRECTION, revenueUsd/costUsd son los ajustes con signo y missionId/environment vienen del movimiento corregido.
 */
public record FinanceMovement(String id, String kind, String description, double revenueUsd, double costUsd,
                              String missionId, String environment, Instant recordedAt, String targetId,
                              String counterparty, String productId) {

    /** Movimientos sin producto (gastos, ventas previas al catálogo). */
    public FinanceMovement(String id, String kind, String description, double revenueUsd, double costUsd,
                           String missionId, String environment, java.time.Instant recordedAt, String targetId,
                           String counterparty) {
        this(id, kind, description, revenueUsd, costUsd, missionId, environment, recordedAt, targetId, counterparty, null);
    }
}

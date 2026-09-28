package com.aicompany.core.model;

/** Registro del fundador desde el Command Center (spec finanzas 2026-09-27, 🔴). */
public record FinanceCorrectionCommand(String targetId, double revenueAdjustmentUsd, double costAdjustmentUsd, String reason, String evidenceDescription, String evidenceLink) {
}

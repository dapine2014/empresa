package com.aicompany.core.model;

/** Registro del fundador desde el Command Center (spec finanzas 2026-09-27, 🔴). */
public record FinanceExpenseCommand(String description, double amountUsd, String missionId, String environment, String evidenceDescription, String evidenceLink) {
}

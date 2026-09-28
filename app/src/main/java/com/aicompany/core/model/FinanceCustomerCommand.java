package com.aicompany.core.model;

/** Registro del fundador desde el Command Center (spec finanzas 2026-09-27, 🔴). */
public record FinanceCustomerCommand(String name, String contact, String missionId, String evidenceDescription, String evidenceLink) {
}

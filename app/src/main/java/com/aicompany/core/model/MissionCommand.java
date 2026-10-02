package com.aicompany.core.model;

import jakarta.validation.constraints.NotBlank;

import java.util.Locale;

/**
 * {@code environment} es opcional: por default una misión se asume
 * {@code PRODUCTION} (trabajo real) — quien inicia una misión de prueba
 * o desarrollo (curl manual, scripts de verificación) debe marcarla
 * explícitamente como {@code "TEST"}. Nunca se adivina por el nombre del
 * {@code missionId} ni ninguna otra heurística.
 */
public record MissionCommand(
        @NotBlank String missionId,
        @NotBlank String instruction,
        String environment,
        FinancialCriteriaCommand financialCriteria,
        String teamId,
        java.util.List<String> databases
) {
    public MissionCommand(String missionId, String instruction, String environment,
                          FinancialCriteriaCommand financialCriteria, String teamId) {
        this(missionId, instruction, environment, financialCriteria, teamId, null);
    }

    /** Spec 2026-10-02 §1: nombres de conexiones de base de datos que usará la misión. */
    public java.util.List<String> databasesOrEmpty() {
        return databases == null ? java.util.List.of() : databases;
    }

    public String environmentOrDefault() {
        return environment == null || environment.isBlank()
                ? "PRODUCTION"
                : environment.toUpperCase(Locale.ROOT);
    }

    /** Exacto (sin cambiar mayúsculas): el backend valida contra el catálogo fijo de equipos. */
    public String teamIdOrNull() {
        return teamId == null || teamId.isBlank() ? null : teamId.strip();
    }
}

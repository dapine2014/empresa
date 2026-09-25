package com.aicompany.core.model;

/** Estrategia de ejecución de un equipo, derivada de {@code Team.type} (catálogo fijo en código). */
public enum TeamExecutionMode {
    ANALYSIS,
    DEVELOPMENT;

    public static TeamExecutionMode forTeamType(String teamType) {
        return "ENGINEERING".equals(teamType) ? DEVELOPMENT : ANALYSIS;
    }
}

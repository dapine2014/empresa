package com.aicompany.core.database;

import java.util.Set;

/**
 * Spec Diego ambientes §2 (seguimiento 2026-10-08): qué SQLState de PostgreSQL es un problema del ambiente y nunca una
 * corrección que un modelo pueda hacer. Función pura. Solo los errores genuinos del SQL de la migración (42601
 * sintaxis, 42P01 tabla inexistente, 42703 columna inexistente, 42P07 tabla duplicada, 23* restricciones, 22*
 * datos) siguen siendo corregibles.
 */
final class SqlStateClassifier {

    /** Clases completas: 08 conexión, 28 autenticación, 53 recursos, 57 intervención del operador/timeout, 58 sistema. */
    private static final Set<String> ENVIRONMENT_CLASSES = Set.of("08", "28", "53", "57", "58");
    /** 55P03 lock_not_available, 42501 insufficient_privilege, 40001 serialization_failure, 40P01 deadlock_detected. */
    private static final Set<String> ENVIRONMENT_CODES = Set.of("55P03", "42501", "40001", "40P01");

    private SqlStateClassifier() {
    }

    /** Sin SQLState (p. ej. una excepción de E/S o un timeout del driver) tampoco es SQL de la migración. */
    static boolean isEnvironmentProblem(String sqlState) {
        if (sqlState == null || sqlState.isBlank()) {
            return true;
        }
        var state = sqlState.strip().toUpperCase(java.util.Locale.ROOT);
        return ENVIRONMENT_CODES.contains(state)
                || (state.length() >= 2 && ENVIRONMENT_CLASSES.contains(state.substring(0, 2)));
    }
}

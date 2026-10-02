package com.aicompany.core.database;

/**
 * Conexión de base de datos del fundador (spec 2026-10-02 §1). Nunca lleva la clave: solo {@code passwordHint}
 * ("****1234"). {@link #toString()} no expone host ni usuario.
 */
public record DatabaseConnection(
        String id,
        String name,
        String engine,
        String host,
        int port,
        String database,
        String username,
        String tls,
        String caCertPem,
        String environment,
        String passwordHint,
        String lastCheckedAt,
        String lastCheckResult
) {
    @Override
    public String toString() {
        return "DatabaseConnection[" + name + ", " + engine + ", " + environment + "]";
    }
}

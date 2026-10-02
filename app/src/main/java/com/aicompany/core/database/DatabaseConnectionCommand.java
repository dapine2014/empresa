package com.aicompany.core.database;

/** Alta o edición de una conexión desde Settings (spec 2026-10-02 §1). {@code password} vacío al editar = conservar. */
public record DatabaseConnectionCommand(
        String name,
        String engine,
        String host,
        Integer port,
        String database,
        String username,
        String password,
        String tls,
        String caCertPem,
        String environment
) {
    @Override
    public String toString() {
        return "DatabaseConnectionCommand[" + name + ", " + engine + "]";
    }
}

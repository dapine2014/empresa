package com.aicompany.core.database;

import java.util.List;
import java.util.Locale;

/**
 * Contrato de variables de una conexión (spec 2026-10-02 §1): lo único que ven Neo, Diego, Iris y Vera. Java lo
 * deriva del nombre; el código generado solo puede leer estas variables, nunca valores reales.
 */
public final class ConnectionContract {

    private static final List<String> SUFFIXES = List.of("HOST", "PORT", "NAME", "USER", "PASSWORD", "SSLMODE");

    private ConnectionContract() {
    }

    public static String prefix(String name) {
        return "DB_" + name.toUpperCase(Locale.ROOT).replace('-', '_') + "_";
    }

    public static List<String> variables(String name) {
        return SUFFIXES.stream().map(s -> prefix(name) + s).toList();
    }

    public static String describe(DatabaseConnection c) {
        return "Base \"" + c.name() + "\" (" + c.engine() + ", entorno " + c.environment() + ", TLS " + c.tls()
                + "). Variables de entorno (las ÚNICAS que puede leer el código; nunca escribas valores): "
                + String.join(", ", variables(c.name()));
    }
}

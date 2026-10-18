package com.aicompany.core.database;

import java.util.Comparator;
import java.util.List;

/**
 * Orden de la cascada de migraciones (Spec Diego ambientes §1): solo conexiones del motor del plan, TEST antes que
 * PRODUCTION, la que nombra el plan primero dentro de su ambiente y luego por nombre.
 */
public final class EnvironmentCascade {

    private EnvironmentCascade() {
    }

    public static List<DatabaseConnection> order(List<DatabaseConnection> ofMission, String engine, String namedFirst) {
        var named = namedFirst == null ? "" : namedFirst;
        return ofMission.stream()
                .filter(c -> engine == null || engine.equals(c.engine()))
                .sorted(Comparator
                        .comparingInt((DatabaseConnection c) -> "PRODUCTION".equals(c.environment()) ? 1 : 0)
                        .thenComparing(c -> c.name().equals(named) ? 0 : 1)
                        .thenComparing(DatabaseConnection::name))
                .toList();
    }
}

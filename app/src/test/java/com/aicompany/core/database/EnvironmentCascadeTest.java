package com.aicompany.core.database;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvironmentCascadeTest {

    private static DatabaseConnection c(String name, String env, String engine) {
        return new DatabaseConnection("id-" + name, name, engine, "h", 5432, "d", "u", "DISABLE", null, env, "****", null, null);
    }

    private static List<String> names(List<DatabaseConnection> l) {
        return l.stream().map(DatabaseConnection::name).toList();
    }

    @Test
    void testBeforeProductionThenByName() {
        var l = List.of(c("zeta", "PRODUCTION", "POSTGRESQL"), c("beta", "TEST", "POSTGRESQL"),
                c("alfa", "PRODUCTION", "POSTGRESQL"), c("alfa-t", "TEST", "POSTGRESQL"));
        assertEquals(List.of("alfa-t", "beta", "alfa", "zeta"), names(EnvironmentCascade.order(l, "POSTGRESQL", "")));
    }

    @Test
    void theNamedOneGoesFirstWithinItsEnvironment() {
        var l = List.of(c("a", "TEST", "POSTGRESQL"), c("b", "TEST", "POSTGRESQL"), c("c", "PRODUCTION", "POSTGRESQL"),
                c("d", "PRODUCTION", "POSTGRESQL"));
        assertEquals(List.of("a", "b", "d", "c"), names(EnvironmentCascade.order(l, "POSTGRESQL", "d")));
        assertEquals(List.of("b", "a", "c", "d"), names(EnvironmentCascade.order(l, "POSTGRESQL", "b")));
    }

    @Test
    void excludesOtherEnginesAndEmptyStaysEmpty() {
        var l = List.of(c("a", "TEST", "MYSQL"), c("b", "TEST", "POSTGRESQL"));
        assertEquals(List.of("b"), names(EnvironmentCascade.order(l, "POSTGRESQL", "")));
        assertEquals(List.of(), EnvironmentCascade.order(List.of(), "POSTGRESQL", ""));
    }
}

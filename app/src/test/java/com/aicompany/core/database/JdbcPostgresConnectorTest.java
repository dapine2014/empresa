package com.aicompany.core.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Revisión final (I-8): un servidor colgado no puede bloquear una misión. */
class JdbcPostgresConnectorTest {

    @Test
    void everyConnectionHasConnectLoginAndSocketTimeouts() {
        var c = new DatabaseConnection("C1", "citas", "POSTGRESQL", "h", 5432, "d", "u", "REQUIRE", null, "TEST",
                "****", null, null);
        var props = JdbcPostgresConnector.properties(c, "p");
        assertEquals("10", props.getProperty("connectTimeout"));
        assertEquals("15", props.getProperty("loginTimeout"));
        assertEquals("330", props.getProperty("socketTimeout"));
        assertEquals("require", props.getProperty("sslmode"));
    }
}

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

    // Revisión final Diego ambientes (I2): el servidor no manda "Detail:" con valores de filas reales.
    @Test
    void theServerErrorDetailIsNeverRequested() {
        var c = new DatabaseConnection("C1", "citas", "POSTGRESQL", "h", 5432, "d", "u", "REQUIRE", null, "TEST",
                "****", null, null);
        assertEquals("false", JdbcPostgresConnector.properties(c, "p").getProperty("logServerErrorDetail"));
    }

    @Test
    void detailWhereAndHintLinesAreStrippedButMessageAndPositionStay() {
        var message = "ERROR: duplicate key value violates unique constraint \"cliente_email_key\"\n"
                + "  Detail: Key (email)=(ana@cliente.com) already exists.\n"
                + "  Hint: revisa ana@cliente.com\n"
                + "  Position: 15\n"
                + "  Where: PL/pgSQL function f() line 3\nSQL statement \"INSERT ... 'ana@cliente.com'\"";
        var clean = DatabaseSchemaApplier.withoutServerDetail(message);
        assertFalse(clean.contains("ana@cliente.com"), clean);
        assertTrue(clean.contains("duplicate key") && clean.contains("Position: 15"), clean);
    }
}

package com.aicompany.core.database;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionContractTest {

    private static DatabaseConnection conn() {
        return new DatabaseConnection("C1", "citas-dev-aws", "POSTGRESQL", "db.secreto.rds.amazonaws.com", 5432, "citas",
                "admin_citas", "REQUIRE", null, "TEST", "****9876", null, null);
    }

    @Test
    void theContractIsDerivedFromTheName() {
        assertEquals("DB_CITAS_DEV_AWS_", ConnectionContract.prefix("citas-dev-aws"));
        assertEquals(List.of("DB_CITAS_DEV_AWS_HOST", "DB_CITAS_DEV_AWS_PORT", "DB_CITAS_DEV_AWS_NAME",
                "DB_CITAS_DEV_AWS_USER", "DB_CITAS_DEV_AWS_PASSWORD", "DB_CITAS_DEV_AWS_SSLMODE"),
                ConnectionContract.variables("citas-dev-aws"));
    }

    @Test
    void theDescriptionForAgentsNeverHasHostUserOrPassword() {
        var text = ConnectionContract.describe(conn());
        assertTrue(text.contains("citas-dev-aws") && text.contains("POSTGRESQL") && text.contains("DB_CITAS_DEV_AWS_HOST"), text);
        assertFalse(text.contains("rds.amazonaws.com"), text);
        assertFalse(text.contains("admin_citas"), text);
        assertFalse(text.contains("9876"), text);
    }
}

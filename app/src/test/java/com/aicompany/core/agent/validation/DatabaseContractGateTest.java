package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseContractGateTest {

    private static final List<String> CONTRACT = List.of("DB_CITAS_HOST", "DB_CITAS_PORT", "DB_CITAS_NAME",
            "DB_CITAS_USER", "DB_CITAS_PASSWORD", "DB_CITAS_SSLMODE");

    @Test
    void readingAVariableOutsideTheContractIsRejected() {
        assertFalse(DatabaseContractGate.check(List.of(new GeneratedFile("src/A.cs",
                "Environment.GetEnvironmentVariable(\"DB_OTRA_HOST\")")), true, CONTRACT).isEmpty());
    }

    @Test
    void migrationsWithoutADeclaredDatabaseAreRejected() {
        assertFalse(DatabaseContractGate.check(List.of(new GeneratedFile("db/postgres/migrations/V1__a.sql", "x;")),
                false, List.of()).isEmpty());
    }

    @Test
    void theContractVariablesPass() {
        assertEquals(List.of(), DatabaseContractGate.check(List.of(new GeneratedFile("src/A.cs",
                "Environment.GetEnvironmentVariable(\"DB_CITAS_HOST\")")), true, CONTRACT));
    }
}

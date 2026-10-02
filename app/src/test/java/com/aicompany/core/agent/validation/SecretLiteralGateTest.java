package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecretLiteralGateTest {

    @Test
    void aConnectionStringInAppSettingsIsRejected() {
        var errors = SecretLiteralGate.check(List.of(new GeneratedFile("src/Citas.Api/appsettings.json",
                "{\"ConnectionStrings\":{\"Db\":\"Host=localhost;Username=postgres;Password=postgres\"}}")), List.of());
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("appsettings.json"), errors.toString());
    }

    @Test
    void aPostgresUriWithCredentialsIsRejected() {
        assertFalse(SecretLiteralGate.check(List.of(new GeneratedFile("src/A.cs",
                "var u = \"postgresql://admin:clave@db:5432/citas\";")), List.of()).isEmpty());
    }

    @Test
    void theRealHostOrUserOfAMissionConnectionIsRejected() {
        assertFalse(SecretLiteralGate.check(List.of(new GeneratedFile("src/A.cs",
                "// conecta a db.secreto.rds.amazonaws.com")), List.of("db.secreto.rds.amazonaws.com", "admin_citas")).isEmpty());
    }

    @Test
    void readingTheContractVariablesIsFine() {
        assertEquals(List.of(), SecretLiteralGate.check(List.of(new GeneratedFile("src/A.cs",
                "var pwd = Environment.GetEnvironmentVariable(\"DB_CITAS_PASSWORD\");\nvar s = $\"Password={pwd}\";")),
                List.of()));
    }
}

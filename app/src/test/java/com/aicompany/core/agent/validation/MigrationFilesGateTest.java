package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MigrationFilesGateTest {

    private static GeneratedFile f(String path, String content) {
        return new GeneratedFile(path, content);
    }

    @Test
    void consecutiveWellNamedMigrationsPass() {
        assertEquals(List.of(), MigrationFilesGate.check(List.of(
                f("db/postgres/migrations/V1__crear_citas.sql", "CREATE TABLE cita (id uuid primary key);"),
                f("db/postgres/migrations/V2__indice_fecha.sql", "CREATE INDEX ix_cita_fecha ON cita (fecha);")), List.of()));
    }

    @Test
    void badNamesGapsAndPsqlCommandsAreRejected() {
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/crear.sql", "x;")), List.of()).isEmpty());
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/V2__a.sql", "x;")), List.of()).isEmpty());
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/V1__a.sql", "\\connect citas\nx;")), List.of()).isEmpty());
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/V1__a.sql", "  ")), List.of()).isEmpty());
    }

    @Test
    void theSequenceContinuesFromWhatIsAlreadyInTheRepository() {
        assertEquals(List.of(), MigrationFilesGate.check(List.of(f("db/postgres/migrations/V3__mas.sql", "x;")),
                List.of("db/postgres/migrations/V1__a.sql", "db/postgres/migrations/V2__b.sql")));
    }
}

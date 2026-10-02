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
                f("db/postgres/migrations/V2__indice_fecha.sql", "CREATE INDEX ix_cita_fecha ON cita (fecha);")), java.util.Map.of()));
    }

    @Test
    void badNamesGapsAndPsqlCommandsAreRejected() {
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/crear.sql", "x;")), java.util.Map.of()).isEmpty());
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/V2__a.sql", "x;")), java.util.Map.of()).isEmpty());
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/V1__a.sql", "\\connect citas\nx;")), java.util.Map.of()).isEmpty());
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/V1__a.sql", "  ")), java.util.Map.of()).isEmpty());
    }

    @Test
    void theSequenceContinuesFromWhatIsAlreadyInTheRepository() {
        assertEquals(List.of(), MigrationFilesGate.check(List.of(f("db/postgres/migrations/V3__mas.sql", "x;")),
                java.util.Map.of("db/postgres/migrations/V1__a.sql", "a;", "db/postgres/migrations/V2__b.sql", "b;")));
    }

    // Revisión final (I-5): una migración que ya existe no se edita; el cambio va en una nueva.
    @Test
    void anExistingMigrationCannotBeEdited() {
        var errors = MigrationFilesGate.check(List.of(f("db/postgres/migrations/V1__a.sql", "CREATE TABLE a (id int, x int);")),
                java.util.Map.of("db/postgres/migrations/V1__a.sql", "CREATE TABLE a (id int);"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("V1__a.sql") && e.contains("V2")), errors.toString());
        assertEquals(List.of(), MigrationFilesGate.check(List.of(f("db/postgres/migrations/V1__a.sql", "a;")),
                java.util.Map.of("db/postgres/migrations/V1__a.sql", "a;")));
    }

    // Revisión final (m-7): Forjai abre la transacción; una migración no la maneja.
    @Test
    void explicitTransactionsAreRejected() {
        assertFalse(MigrationFilesGate.check(List.of(f("db/postgres/migrations/V1__a.sql",
                "BEGIN;\nCREATE TABLE a (id int);\nCOMMIT;")), java.util.Map.of()).isEmpty());
    }
}

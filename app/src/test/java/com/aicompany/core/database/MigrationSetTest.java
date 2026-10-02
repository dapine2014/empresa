package com.aicompany.core.database;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MigrationSetTest {

    private static final Map<String, String> FILES = Map.of(
            "db/postgres/migrations/V2__indice.sql", "CREATE INDEX i ON cita (fecha);",
            "db/postgres/migrations/V1__crear.sql", "CREATE TABLE cita (id uuid primary key, fecha date);",
            "src/Citas.Api/Program.cs", "class P {}");

    @Test
    void migrationsAreParsedInOrderWithAChecksum() {
        var all = MigrationSet.parse(FILES);
        assertEquals(List.of(1, 2), all.stream().map(MigrationSet.Migration::version).toList());
        assertEquals(64, all.get(0).checksum().length());
        assertEquals("crear", all.get(0).description());
    }

    @Test
    void onlyThePendingOnesAreApplied() {
        var all = MigrationSet.parse(FILES);
        var plan = MigrationSet.pending(all, List.of(new MigrationSet.AppliedMigration(1, all.get(0).checksum())));
        assertNull(plan.error());
        assertEquals(List.of(2), plan.toApply().stream().map(MigrationSet.Migration::version).toList());
    }

    @Test
    void anEditedAppliedMigrationStopsEverything() {
        var all = MigrationSet.parse(FILES);
        var plan = MigrationSet.pending(all, List.of(new MigrationSet.AppliedMigration(1, "otro-checksum")));
        assertTrue(plan.toApply().isEmpty());
        assertTrue(plan.error().contains("V1"), plan.error());
    }

    @Test
    void anAppliedMigrationMissingFromTheRepositoryIsReported() {
        var plan = MigrationSet.pending(MigrationSet.parse(Map.of()),
                List.of(new MigrationSet.AppliedMigration(1, "x")));
        assertTrue(plan.error().contains("V1"), plan.error());
    }
}

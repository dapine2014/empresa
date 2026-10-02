package com.aicompany.core.database;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 2026-10-02 Testing: PostgreSQL efímero solo para este test; nunca en el sandbox de las misiones. */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseSchemaApplierIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private final DatabaseSchemaApplier applier = new DatabaseSchemaApplier(new JdbcPostgresConnector());

    private DatabaseConnection conn(String database) {
        return new DatabaseConnection("C1", "citas-it", "POSTGRESQL", PG.getHost(), PG.getMappedPort(5432), database,
                PG.getUsername(), "DISABLE", null, "TEST", "****", null, null);
    }

    private static List<MigrationSet.Migration> migrations(Map<String, String> files) {
        return MigrationSet.parse(files);
    }

    @Test
    void createsTheDatabaseAppliesInOrderAndKeepsTheHistory() {
        var files = Map.of(
                "db/postgres/migrations/V1__crear.sql", "CREATE TABLE cita (id serial primary key, fecha date not null);",
                "db/postgres/migrations/V2__indice.sql", "CREATE INDEX ix_cita_fecha ON cita (fecha);");

        var first = applier.apply(conn("citas_it"), PG.getPassword(), migrations(files), "M-1", "abc");
        var second = applier.apply(conn("citas_it"), PG.getPassword(), migrations(files), "M-1", "abc");

        assertTrue(first.createdDatabase());
        assertEquals(List.of(1, 2), first.applied());
        assertNull(first.error());
        assertEquals(List.of(), second.applied());
        assertNull(second.error());
    }

    @Test
    void aFailingMigrationStopsThereAndKeepsThePreviousOnes() {
        var files = Map.of(
                "db/postgres/migrations/V1__ok.sql", "CREATE TABLE a (id int);",
                "db/postgres/migrations/V2__mal.sql", "CREATE TABL b (id int);",
                "db/postgres/migrations/V3__nunca.sql", "CREATE TABLE c (id int);");

        var result = applier.apply(conn("parcial_it"), PG.getPassword(), migrations(files), "M-2", "abc");

        assertEquals(List.of(1), result.applied());
        assertEquals(2, result.failedVersion());
        assertTrue(result.error().contains("syntax"), result.error());
    }

    @Test
    void theApplyResultNeverContainsThePassword() {
        var bad = new DatabaseConnection("C1", "citas-it", "POSTGRESQL", PG.getHost(), PG.getMappedPort(5432), "x",
                PG.getUsername(), "DISABLE", null, "TEST", "****", null, null);
        var result = applier.apply(bad, "clave-incorrecta-123", migrations(Map.of()), "M-3", "abc");
        assertNotNull(result.error());
        assertFalse(result.error().contains("clave-incorrecta-123"), result.error());
    }
}

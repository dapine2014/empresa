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

    // Spec Diego ambientes §1: si el primer ambiente falla, el siguiente no se toca (ni siquiera se crea su base).
    @Test
    void aBrokenFirstEnvironmentLeavesTheSecondUntouched() throws Exception {
        var test = new DatabaseConnection("C1", "casc-test", "POSTGRESQL", PG.getHost(), PG.getMappedPort(5432),
                "casc_test_it", PG.getUsername(), "DISABLE", null, "TEST", "****", null, null);
        var prod = new DatabaseConnection("C2", "casc-prod", "POSTGRESQL", PG.getHost(), PG.getMappedPort(5432),
                "casc_prod_it", PG.getUsername(), "DISABLE", null, "PRODUCTION", "****", null, null);
        var connections = org.mockito.Mockito.mock(DatabaseConnectionService.class);
        var workspace = org.mockito.Mockito.mock(com.aicompany.core.service.DevelopmentWorkspaceService.class);
        org.mockito.Mockito.when(connections.connectionsOf("M-4")).thenReturn(List.of(prod, test));
        org.mockito.Mockito.when(connections.password(org.mockito.ArgumentMatchers.any())).thenReturn(PG.getPassword());
        org.mockito.Mockito.when(workspace.filesAtCommit("M-4", "abc")).thenReturn(List.of("db/postgres/migrations/V1__mal.sql"));
        org.mockito.Mockito.when(workspace.readFileAtCommit("M-4", "abc", "db/postgres/migrations/V1__mal.sql"))
                .thenReturn("CREATE TABL a (id int);");
        var service = new DatabaseApplyService(connections, applier, workspace,
                org.mockito.Mockito.mock(com.aicompany.core.service.MissionMemoryService.class),
                org.mockito.Mockito.mock(com.aicompany.core.event.CompanyEventPublisher.class));
        var plan = new com.aicompany.core.agent.model.TeamPlan("x", null, null, List.of(), List.of(), "DOTNET_APP",
                List.of(), List.of(), new com.aicompany.core.agent.model.TeamPlan.DatabaseNeed("POSTGRESQL", ""));

        var result = service.apply("M-4", "abc", plan);

        assertFalse(result.allApplied());
        assertEquals(1, result.environments().get(0).failedVersion());
        assertTrue(result.environments().get(1).skipped());
        try (var c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var rs = c.createStatement().executeQuery("SELECT 1 FROM pg_database WHERE datname='casc_prod_it'")) {
            assertFalse(rs.next());
        }
    }
}

package com.aicompany.core.database;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.DevelopmentWorkspaceService;
import com.aicompany.core.service.MissionMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabaseApplyServiceTest {

    private final DatabaseConnectionService connections = mock(DatabaseConnectionService.class);
    private final DatabaseSchemaApplier applier = mock(DatabaseSchemaApplier.class);
    private final DevelopmentWorkspaceService workspace = mock(DevelopmentWorkspaceService.class);
    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final DatabaseApplyService service = new DatabaseApplyService(connections, applier, workspace, memory, events);

    private static final DatabaseConnection CITAS = new DatabaseConnection("C1", "citas-dev-aws", "POSTGRESQL",
            "db.secreto.rds.amazonaws.com", 5432, "citas", "admin", "REQUIRE", null, "TEST", "****9876", null, null);

    private static TeamPlan plan(String connectionName) {
        return new TeamPlan("Citas", null, null, List.of(), List.of(), "DOTNET_APP", List.of(), List.of(),
                new TeamPlan.DatabaseNeed("POSTGRESQL", connectionName));
    }

    @Test
    void appliesTheMigrationsOfTheVerifiedCommitAndRecordsEverything() throws Exception {
        when(connections.connectionsOf("M-1")).thenReturn(List.of(CITAS));
        when(connections.password(CITAS)).thenReturn("S3cret-9876");
        when(workspace.filesAtCommit("M-1", "abc")).thenReturn(List.of("db/postgres/migrations/V1__crear.sql", "src/A.cs"));
        when(workspace.readFileAtCommit("M-1", "abc", "db/postgres/migrations/V1__crear.sql")).thenReturn("CREATE TABLE a (id int);");
        when(applier.apply(eq(CITAS), eq("S3cret-9876"), anyList(), eq("M-1"), eq("abc")))
                .thenReturn(new DatabaseSchemaApplier.ApplyResult(List.of(1), null, null, true));

        var text = service.apply("M-1", "abc", plan("citas-dev-aws")).text();

        assertTrue(text.contains("V1") && text.contains("citas-dev-aws"), text);
        assertFalse(text.contains("S3cret-9876") || text.contains("rds.amazonaws.com"), text);
        verify(memory).createTask("M-1-DATABASE", "M-1", "devops", "DATABASE_APPLY");
        verify(memory).updateTask(eq("M-1-DATABASE"), eq("COMPLETED"), anyString());
        verify(memory).recordDatabaseApply(eq("M-1"), eq("citas-dev-aws"), eq("TEST"), eq(List.of(1)), isNull(), anyString(), eq("APPLIED"));
        verify(memory).recordEvidence(eq("M-1-DATABASE"), eq("M-1"), eq("devops"), anyList());
        verify(events).publish(eq("EMPRESA_DATABASE_SCHEMA_APPLIED"), eq("M-1"), eq("M-1-DATABASE"), eq("forjai"), anyMap());
    }

    @Test
    void withoutAConnectionTheMissionGetsAPendingNote() {
        when(connections.connectionsOf("M-1")).thenReturn(List.of());
        var text = service.apply("M-1", "abc", plan("")).text();
        assertTrue(text.contains("Falta la conexión PostgreSQL") && text.contains("aplica el esquema de M-1"), text);
        verifyNoInteractions(applier);
    }

    @Test
    void aServerErrorIsRecordedWithoutThePassword() throws Exception {
        when(connections.connectionsOf("M-1")).thenReturn(List.of(CITAS));
        when(connections.password(CITAS)).thenReturn("S3cret-9876");
        when(workspace.filesAtCommit("M-1", "abc")).thenReturn(List.of());
        when(applier.apply(any(), any(), anyList(), any(), any()))
                .thenReturn(new DatabaseSchemaApplier.ApplyResult(List.of(), 1, "V1 falló: permission denied", false));

        var text = service.apply("M-1", "abc", plan("citas-dev-aws")).text();

        assertTrue(text.contains("Esquema no aplicado") && text.contains("permission denied"), text);
        verify(memory).updateTask(eq("M-1-DATABASE"), eq("FAILED"), contains("permission denied"));
        verify(events).publish(eq("EMPRESA_DATABASE_SCHEMA_FAILED"), eq("M-1"), eq("M-1-DATABASE"), eq("forjai"), anyMap());
    }

    @Test
    void onDemandItRefusesWhenTheCodeIsNotVerified() {
        when(memory.lastValidationStatus("M-1")).thenReturn(java.util.Optional.of("FAILED"));
        assertThrows(IllegalStateException.class, () -> service.applyLatest("M-1"));
    }

    private static com.aicompany.core.model.MissionResponse mission(com.aicompany.core.model.MissionStatus status) {
        return new com.aicompany.core.model.MissionResponse("M-1", status, "TEST", 100, "x", "x",
                java.time.Instant.now(), null, "TEAM-DEVELOPMENT");
    }

    // Revisión final (I-4): a pedido se aplica el commit VERIFICADO, nunca el HEAD de una ronda en curso.
    @Test
    void onDemandItRefusesWhileTheMissionIsRunning() {
        when(memory.lastValidationStatus("M-1")).thenReturn(java.util.Optional.of("VERIFIED"));
        when(memory.find("M-1")).thenReturn(java.util.Optional.of(mission(com.aicompany.core.model.MissionStatus.DELEGATING)));
        assertThrows(IllegalStateException.class, () -> service.applyLatest("M-1"));
        verifyNoInteractions(applier);
    }

    // Revisión 2026-10-06 §4: BLOCKED cuenta como detenida (como FAILED).
    @Test
    void onDemandItAcceptsABlockedMission() throws Exception {
        when(memory.lastValidationStatus("M-1")).thenReturn(java.util.Optional.of("VERIFIED"));
        when(memory.find("M-1")).thenReturn(java.util.Optional.of(mission(com.aicompany.core.model.MissionStatus.BLOCKED)));
        when(memory.verifiedCommit("M-1")).thenReturn(java.util.Optional.of("verificado"));
        when(memory.lastTeamPlanJson("M-1")).thenReturn(java.util.Optional.of(
                "{\"summary\":\"Citas\",\"stackProfile\":\"DOTNET_APP\",\"tasks\":[],"
                        + "\"database\":{\"engine\":\"POSTGRESQL\",\"connectionName\":\"citas-dev-aws\"}}"));
        when(connections.connectionsOf("M-1")).thenReturn(List.of(CITAS));
        when(connections.password(CITAS)).thenReturn("S3cret-9876");
        when(workspace.filesAtCommit("M-1", "verificado")).thenReturn(List.of());
        when(applier.apply(any(), any(), anyList(), any(), any()))
                .thenReturn(new DatabaseSchemaApplier.ApplyResult(List.of(), null, null, false));

        service.applyLatest("M-1");

        verify(applier).apply(eq(CITAS), eq("S3cret-9876"), anyList(), eq("M-1"), eq("verificado"));
    }

    @Test
    void onDemandItUsesTheVerifiedCommit() throws Exception {
        when(memory.lastValidationStatus("M-1")).thenReturn(java.util.Optional.of("VERIFIED"));
        when(memory.find("M-1")).thenReturn(java.util.Optional.of(mission(com.aicompany.core.model.MissionStatus.AWAITING_INVESTOR)));
        when(memory.verifiedCommit("M-1")).thenReturn(java.util.Optional.of("verificado"));
        when(memory.lastTeamPlanJson("M-1")).thenReturn(java.util.Optional.of(
                "{\"summary\":\"Citas\",\"stackProfile\":\"DOTNET_APP\",\"tasks\":[],"
                        + "\"database\":{\"engine\":\"POSTGRESQL\",\"connectionName\":\"citas-dev-aws\"}}"));
        when(connections.connectionsOf("M-1")).thenReturn(List.of(CITAS));
        when(connections.password(CITAS)).thenReturn("S3cret-9876");
        when(workspace.filesAtCommit("M-1", "verificado")).thenReturn(List.of());
        when(applier.apply(any(), any(), anyList(), any(), any()))
                .thenReturn(new DatabaseSchemaApplier.ApplyResult(List.of(), null, null, false));

        service.applyLatest("M-1");

        verify(applier).apply(eq(CITAS), eq("S3cret-9876"), anyList(), eq("M-1"), eq("verificado"));
        verify(workspace, never()).headSha(any());
    }

    private static DatabaseConnection prod() {
        return new DatabaseConnection("C2", "citas-prod", "POSTGRESQL", "prod.secreto.rds.amazonaws.com", 5432, "citas",
                "admin", "REQUIRE", null, "PRODUCTION", "****1111", null, null);
    }

    private void stubFiles() throws Exception {
        when(workspace.filesAtCommit("M-1", "abc")).thenReturn(List.of("db/postgres/migrations/V1__crear.sql"));
        when(workspace.readFileAtCommit("M-1", "abc", "db/postgres/migrations/V1__crear.sql")).thenReturn("CREATE TABLE a (id int);");
    }

    @Test
    void cascadeAppliesTestThenProductionInThatOrder() throws Exception {
        var prod = prod();
        when(connections.connectionsOf("M-1")).thenReturn(List.of(prod, CITAS));
        when(connections.password(any())).thenReturn("S3cret-9876");
        stubFiles();
        when(applier.apply(any(), any(), anyList(), any(), any()))
                .thenReturn(new DatabaseSchemaApplier.ApplyResult(List.of(1), null, null, true));

        var result = service.apply("M-1", "abc", plan(""));

        assertTrue(result.allApplied());
        assertEquals(List.of("TEST", "PRODUCTION"), result.environments().stream().map(DatabaseApplyService.EnvironmentResult::environment).toList());
        var order = inOrder(applier);
        order.verify(applier).apply(eq(CITAS), any(), anyList(), eq("M-1"), eq("abc"));
        order.verify(applier).apply(eq(prod), any(), anyList(), eq("M-1"), eq("abc"));
        verify(memory, times(2)).recordEvidence(eq("M-1-DATABASE"), eq("M-1"), eq("devops"), anyList());
        verify(memory).updateTask(eq("M-1-DATABASE"), eq("COMPLETED"), anyString());
        verify(events).publish(eq("EMPRESA_DATABASE_SCHEMA_APPLIED"), eq("M-1"), eq("M-1-DATABASE"), eq("forjai"),
                argThat(m -> "TEST".equals(m.get("environment"))));
        verify(events).publish(eq("EMPRESA_DATABASE_SCHEMA_APPLIED"), eq("M-1"), eq("M-1-DATABASE"), eq("forjai"),
                argThat(m -> "PRODUCTION".equals(m.get("environment"))));
    }

    @Test
    void aFailingTestEnvironmentSkipsProductionAndNeverCallsTheApplierForIt() throws Exception {
        var prod = prod();
        when(connections.connectionsOf("M-1")).thenReturn(List.of(CITAS, prod));
        when(connections.password(any())).thenReturn("S3cret-9876");
        stubFiles();
        when(applier.apply(eq(CITAS), any(), anyList(), any(), any()))
                .thenReturn(new DatabaseSchemaApplier.ApplyResult(List.of(), 3, "V3 falló: boom", false));

        var result = service.apply("M-1", "abc", plan(""));

        assertFalse(result.allApplied());
        assertTrue(result.environments().get(1).skipped());
        assertTrue(result.text().contains("sin aplicar, falló TEST") && result.text().contains("V3"), result.text());
        verify(applier, never()).apply(eq(prod), any(), anyList(), any(), any());
        verify(memory).recordDatabaseApply(eq("M-1"), eq("citas-prod"), eq("PRODUCTION"), eq(List.of()), isNull(), anyString(), eq("SKIPPED"));
        verify(memory).updateTask(eq("M-1-DATABASE"), eq("FAILED"), anyString());
    }

    // Review Focus 2: una conexión que lanza es la falla de ese ambiente, redactada y registrada.
    @Test
    void aConnectionThatThrowsFailsItsEnvironmentWithARedactedError() throws Exception {
        var prod = prod();
        when(connections.connectionsOf("M-1")).thenReturn(List.of(CITAS, prod));
        when(connections.password(any())).thenReturn("S3cret-9876");
        stubFiles();
        when(applier.apply(eq(CITAS), any(), anyList(), any(), any()))
                .thenReturn(new DatabaseSchemaApplier.ApplyResult(List.of(1), null, null, false));
        when(applier.apply(eq(prod), any(), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("no route to prod.secreto.rds.amazonaws.com with S3cret-9876"));

        var result = service.apply("M-1", "abc", plan(""));

        assertFalse(result.allApplied());
        var err = result.environments().get(1).error();
        assertFalse(err.contains("S3cret-9876") || err.contains("rds.amazonaws.com"), err);
        verify(memory).recordDatabaseApply(eq("M-1"), eq("citas-prod"), eq("PRODUCTION"), eq(List.of()), isNull(),
                argThat(t -> !t.contains("S3cret-9876")), eq("FAILED"));
        verify(events).publish(eq("EMPRESA_DATABASE_SCHEMA_FAILED"), eq("M-1"), eq("M-1-DATABASE"), eq("forjai"), anyMap());
    }
}

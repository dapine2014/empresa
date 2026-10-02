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

        var text = service.apply("M-1", "abc", plan("citas-dev-aws"));

        assertTrue(text.contains("V1") && text.contains("citas-dev-aws"), text);
        assertFalse(text.contains("S3cret-9876") || text.contains("rds.amazonaws.com"), text);
        verify(memory).createTask("M-1-DATABASE", "M-1", "forjai", "DATABASE_APPLY");
        verify(memory).updateTask(eq("M-1-DATABASE"), eq("COMPLETED"), anyString());
        verify(memory).recordEvidence(eq("M-1-DATABASE"), eq("M-1"), eq("forjai"), anyList());
        verify(events).publish(eq("EMPRESA_DATABASE_SCHEMA_APPLIED"), eq("M-1"), eq("M-1-DATABASE"), eq("forjai"), anyMap());
    }

    @Test
    void withoutAConnectionTheMissionGetsAPendingNote() {
        when(connections.connectionsOf("M-1")).thenReturn(List.of());
        var text = service.apply("M-1", "abc", plan(""));
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

        var text = service.apply("M-1", "abc", plan("citas-dev-aws"));

        assertTrue(text.contains("Esquema no aplicado") && text.contains("permission denied"), text);
        verify(memory).updateTask(eq("M-1-DATABASE"), eq("FAILED"), contains("permission denied"));
        verify(events).publish(eq("EMPRESA_DATABASE_SCHEMA_FAILED"), eq("M-1"), eq("M-1-DATABASE"), eq("forjai"), anyMap());
    }

    @Test
    void onDemandItRefusesWhenTheCodeIsNotVerified() {
        when(memory.lastValidationStatus("M-1")).thenReturn(java.util.Optional.of("FAILED"));
        assertThrows(IllegalStateException.class, () -> service.applyLatest("M-1"));
    }
}

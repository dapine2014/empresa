package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cancelar una misión en curso (decisión del fundador 2026-10-15): antes no había forma y el Pong se canceló a mano en
 * Neo4j con company-core detenido.
 */
class MissionCancelTest {

    private static final String ID = "MISSION-PONG-1";

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final MissionExecutor executor = mock(MissionExecutor.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final MissionCancellations cancellations = new MissionCancellations();
    private final MissionService service = new MissionService(memory, executor, events, mock(TeamMemoryService.class),
            mock(DevelopmentWorkspaceService.class));

    {
        service.setCancellations(cancellations);
    }

    private static MissionResponse mission(MissionStatus status) {
        return new MissionResponse(ID, status, "PRODUCTION", 60, "Sandbox", "", Instant.EPOCH, null, "TEAM-DEVELOPMENT");
    }

    @Test
    void aRunningMissionIsCancelledWithItsOpenTasksAndTheWorkIsFlagged() {
        when(memory.find(ID)).thenReturn(Optional.of(mission(MissionStatus.EVALUATING)), Optional.of(mission(MissionStatus.CANCELLED)));
        when(memory.cancelOpenTasks(ID)).thenReturn(2);

        var response = service.cancel(ID, "human");

        assertEquals(MissionStatus.CANCELLED, response.status());
        verify(memory).updateMission(eq(ID), eq(MissionStatus.CANCELLED), eq(100), eq("Cancelada"), contains("EVALUATING"));
        verify(memory).cancelOpenTasks(ID);
        verify(events).publishMission(eq("EMPRESA_MISSION_UPDATED"), eq(ID), eq("CANCELLED"), eq(100), eq("Cancelada"), anyString());
        assertTrue(cancellations.isCancelled(ID));
    }

    @Test
    void aFinishedMissionCannotBeCancelled() {
        for (var status : new MissionStatus[]{MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.CANCELLED}) {
            when(memory.find(ID)).thenReturn(Optional.of(mission(status)));
            var ex = assertThrows(IllegalStateException.class, () -> service.cancel(ID, "human"));
            assertTrue(ex.getMessage().contains("ya terminó"), ex.getMessage());
        }
        verify(memory, never()).updateMission(anyString(), any(), anyInt(), anyString(), anyString());
        assertFalse(cancellations.isCancelled(ID));
    }

    @Test
    void aMissionThatDoesNotExistIsAnError() {
        when(memory.find(ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.cancel(ID, "human"));
    }

    @Test
    void aCancelledMissionStillStoppingCannotBeDeletedYet() {
        when(memory.find(ID)).thenReturn(Optional.of(mission(MissionStatus.CANCELLED)));
        when(executor.isLive(ID)).thenReturn(true);

        var ex = assertThrows(IllegalStateException.class, () -> service.delete(ID));
        assertTrue(ex.getMessage().contains("deteniendo"), ex.getMessage());
        verify(memory, never()).deleteMission(anyString());
    }

    @Test
    void theModelCallOfACancelledMissionIsCutBeforeCallingTheModel() {
        cancellations.cancel(ID);

        assertThrows(MissionCancellations.MissionCancelledException.class,
                () -> ModelCallContext.with(ID, null, cancellations::checkCurrent));
        assertDoesNotThrow(() -> ModelCallContext.with("MISSION-OTRA", null, cancellations::checkCurrent));
        assertDoesNotThrow(cancellations::checkCurrent);
    }

    @Test
    void theSandboxIsNotCalledForACancelledMission() {
        var sandbox = new SandboxRunnerClient(org.springframework.web.client.RestClient.builder()
                .baseUrl("http://runner.invalid").build(), "t");
        sandbox.setCancellations(cancellations);
        cancellations.cancel(ID);

        assertThrows(MissionCancellations.MissionCancelledException.class,
                () -> sandbox.verify(ID, "abc", "GODOT_DOTNET_GAME"));
    }
}

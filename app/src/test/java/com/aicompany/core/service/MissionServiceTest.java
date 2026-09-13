package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissionServiceTest {

    @Test
    void startPersistsMissionAndSubmitsItForAsynchronousExecution() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);
        var mission = new MissionResponse(
                "MISSION-001", MissionStatus.CREATED, 0,
                "Creada", "Misión recibida", Instant.parse("2026-09-12T00:00:00Z")
        );

        when(executor.executeAsync("MISSION-001", "Investigar una oportunidad"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(memory.find("MISSION-001")).thenReturn(Optional.of(mission));

        var service = new MissionService(memory, executor, eventPublisher);
        var response = service.start("MISSION-001", "Investigar una oportunidad");

        assertEquals(mission, response);
        verify(memory).ensureMission("MISSION-001", "Investigar una oportunidad");
        verify(executor).executeAsync("MISSION-001", "Investigar una oportunidad");
        verify(memory).find("MISSION-001");
        verify(eventPublisher).publishMission(
                "EMPRESA_MISSION_CREATED", "MISSION-001", "CREATED",
                0, "Creada", "Misión recibida"
        );
    }
}

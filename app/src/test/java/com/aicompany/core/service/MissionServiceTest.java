package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    @Test
    void returnsEmptyWhenRecordingDecisionOnMissionThatDoesNotExist() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);

        when(memory.find("MISSION-404")).thenReturn(Optional.empty());

        var service = new MissionService(memory, executor, eventPublisher);
        var response = service.recordDecision(
                "MISSION-404",
                new DecisionCommand(InvestorDecision.APPROVE, "Se ve bien")
        );

        assertTrue(response.isEmpty());
        verify(memory, never()).recordDecision(anyString(), anyString(), any(), anyString());
    }

    @Test
    void rejectsDecisionWhenMissionIsStillRunning() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);

        var running = new MissionResponse(
                "MISSION-001", MissionStatus.WAITING_AGENT_RESULTS, 30,
                "Trabajo paralelo", "Los agentes están trabajando en paralelo.",
                Instant.parse("2026-09-12T00:00:00Z")
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(running));

        var service = new MissionService(memory, executor, eventPublisher);

        assertThrows(IllegalStateException.class, () -> service.recordDecision(
                "MISSION-001",
                new DecisionCommand(InvestorDecision.APPROVE, "Se ve bien")
        ));

        verify(memory, never()).recordDecision(anyString(), anyString(), any(), anyString());
    }

    @Test
    void approvingAMissionMarksItCompleted() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);

        var awaitingInvestor = new MissionResponse(
                "MISSION-001", MissionStatus.AWAITING_INVESTOR, 95,
                "Recomendación", "informe final",
                Instant.parse("2026-09-12T00:00:00Z")
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(awaitingInvestor));

        var service = new MissionService(memory, executor, eventPublisher);
        var response = service.recordDecision(
                "MISSION-001",
                new DecisionCommand(InvestorDecision.APPROVE, "Datos suficientes, aprobado")
        );

        assertTrue(response.isPresent());
        assertEquals(InvestorDecision.APPROVE, response.get().decision());

        verify(memory).recordDecision(
                eq("MISSION-001"), anyString(), eq(InvestorDecision.APPROVE),
                eq("Datos suficientes, aprobado")
        );
        verify(memory).updateMission(
                eq("MISSION-001"), eq(MissionStatus.COMPLETED), anyInt(), anyString(), anyString()
        );
        verify(eventPublisher).publish(
                eq("EMPRESA_MISSION_DECISION_RECORDED"), eq("MISSION-001"), any(), eq("human"), any()
        );
    }

    @Test
    void rejectingAMissionMarksItCancelled() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);

        var awaitingInvestor = new MissionResponse(
                "MISSION-001", MissionStatus.AWAITING_INVESTOR, 95,
                "Recomendación", "informe final",
                Instant.parse("2026-09-12T00:00:00Z")
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(awaitingInvestor));

        var service = new MissionService(memory, executor, eventPublisher);
        service.recordDecision(
                "MISSION-001",
                new DecisionCommand(InvestorDecision.REJECT, "No hay evidencia real de demanda")
        );

        verify(memory).updateMission(
                eq("MISSION-001"), eq(MissionStatus.CANCELLED), anyInt(), anyString(), anyString()
        );
    }

    @Test
    void requestingMoreEvidenceDoesNotChangeMissionStatus() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);

        var awaitingInvestor = new MissionResponse(
                "MISSION-001", MissionStatus.AWAITING_INVESTOR, 95,
                "Recomendación", "informe final",
                Instant.parse("2026-09-12T00:00:00Z")
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(awaitingInvestor));

        var service = new MissionService(memory, executor, eventPublisher);
        var response = service.recordDecision(
                "MISSION-001",
                new DecisionCommand(InvestorDecision.REQUEST_MORE_EVIDENCE, "Falta validar precios reales")
        );

        assertTrue(response.isPresent());
        verify(memory).recordDecision(
                eq("MISSION-001"), anyString(), eq(InvestorDecision.REQUEST_MORE_EVIDENCE), anyString()
        );
        verify(memory, never()).updateMission(anyString(), any(), anyInt(), anyString(), anyString());
        verify(eventPublisher).publish(
                eq("EMPRESA_MISSION_DECISION_RECORDED"), eq("MISSION-001"), any(), eq("human"), any()
        );
    }

    @Test
    void allowsDecisionOnAFailedMission() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);

        var failed = new MissionResponse(
                "MISSION-001", MissionStatus.FAILED, 100,
                "Error", "Los 5 agentes fallaron",
                Instant.parse("2026-09-12T00:00:00Z")
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(failed));

        var service = new MissionService(memory, executor, eventPublisher);
        var response = service.recordDecision(
                "MISSION-001",
                new DecisionCommand(InvestorDecision.REJECT, "Confirmado, no seguir con esto")
        );

        assertTrue(response.isPresent());
    }
}

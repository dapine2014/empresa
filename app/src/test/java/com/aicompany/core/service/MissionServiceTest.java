package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.FinancialCriteriaCommand;
import com.aicompany.core.model.FinancialCriteriaResponse;
import com.aicompany.core.model.FinancialMetric;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissionServiceTest {

    private final TeamMemoryService teamMemory = mock(TeamMemoryService.class);

    @Test
    void startPersistsMissionAndSubmitsItForAsynchronousExecution() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);
        var mission = new MissionResponse(
                "MISSION-001", MissionStatus.CREATED, "PRODUCTION", 0,
                "Creada", "Misión recibida", Instant.parse("2026-09-12T00:00:00Z"), null
        );

        when(executor.executeAsync("MISSION-001", "Investigar una oportunidad"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(memory.find("MISSION-001")).thenReturn(Optional.of(mission));

        var service = new MissionService(memory, executor, eventPublisher, teamMemory);
        var response = service.start("MISSION-001", "Investigar una oportunidad", "PRODUCTION", null);

        assertEquals(mission, response);
        verify(memory).ensureMission("MISSION-001", "Investigar una oportunidad", "PRODUCTION", null, null);
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

        var service = new MissionService(memory, executor, eventPublisher, teamMemory);
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
                "MISSION-001", MissionStatus.WAITING_AGENT_RESULTS, "PRODUCTION", 30,
                "Trabajo paralelo", "Los agentes están trabajando en paralelo.",
                Instant.parse("2026-09-12T00:00:00Z"), null
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(running));

        var service = new MissionService(memory, executor, eventPublisher, teamMemory);

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
                "MISSION-001", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95,
                "Recomendación", "informe final",
                Instant.parse("2026-09-12T00:00:00Z"), null
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(awaitingInvestor));

        var service = new MissionService(memory, executor, eventPublisher, teamMemory);
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
                "MISSION-001", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95,
                "Recomendación", "informe final",
                Instant.parse("2026-09-12T00:00:00Z"), null
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(awaitingInvestor));

        var service = new MissionService(memory, executor, eventPublisher, teamMemory);
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
                "MISSION-001", MissionStatus.AWAITING_INVESTOR, "PRODUCTION", 95,
                "Recomendación", "informe final",
                Instant.parse("2026-09-12T00:00:00Z"), null
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(awaitingInvestor));

        var service = new MissionService(memory, executor, eventPublisher, teamMemory);
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
                "MISSION-001", MissionStatus.FAILED, "PRODUCTION", 100,
                "Error", "Los 5 agentes fallaron",
                Instant.parse("2026-09-12T00:00:00Z"), null
        );
        when(memory.find("MISSION-001")).thenReturn(Optional.of(failed));

        var service = new MissionService(memory, executor, eventPublisher, teamMemory);
        var response = service.recordDecision(
                "MISSION-001",
                new DecisionCommand(InvestorDecision.REJECT, "Confirmado, no seguir con esto")
        );

        assertTrue(response.isPresent());
    }

    @Test
    void startPersistsFinancialCriteriaWhenProvided() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);
        var criteria = new FinancialCriteriaCommand(FinancialMetric.NET_PROFIT, 1000.0, "USD", LocalDate.now().plusDays(30));
        var mission = new MissionResponse(
                "MISSION-001", MissionStatus.CREATED, "PRODUCTION", 0,
                "Creada", "Misión recibida", Instant.parse("2026-09-12T00:00:00Z"),
                new FinancialCriteriaResponse(FinancialMetric.NET_PROFIT, 1000.0, "USD", LocalDate.now().plusDays(30))
        );

        when(executor.executeAsync("MISSION-001", "Investigar una oportunidad"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(memory.find("MISSION-001")).thenReturn(Optional.of(mission));

        var service = new MissionService(memory, executor, eventPublisher, teamMemory);
        service.start("MISSION-001", "Investigar una oportunidad", "PRODUCTION", criteria);

        verify(memory).ensureMission("MISSION-001", "Investigar una oportunidad", "PRODUCTION", criteria, null);
    }

    @Test
    void rejectsFinancialCriteriaWithNonPositiveTargetAmount() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);
        var criteria = new FinancialCriteriaCommand(FinancialMetric.NET_PROFIT, 0.0, "USD", null);

        var service = new MissionService(memory, executor, eventPublisher, teamMemory);

        assertThrows(IllegalArgumentException.class,
                () -> service.start("MISSION-001", "Investigar", "PRODUCTION", criteria));

        verify(memory, never()).ensureMission(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsFinancialCriteriaWithDeadlineInThePast() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        var eventPublisher = mock(CompanyEventPublisher.class);
        var criteria = new FinancialCriteriaCommand(FinancialMetric.NET_PROFIT, 1000.0, "USD", LocalDate.now().minusDays(1));

        var service = new MissionService(memory, executor, eventPublisher, teamMemory);

        assertThrows(IllegalArgumentException.class,
                () -> service.start("MISSION-001", "Investigar", "PRODUCTION", criteria));

        verify(memory, never()).ensureMission(any(), any(), any(), any(), any());
    }

    private static MissionResponse missionIn(String missionId, MissionStatus status) {
        return new MissionResponse(
                missionId, status, "TEST", 100,
                "Paso", "mensaje",
                Instant.parse("2026-09-12T00:00:00Z"), null
        );
    }

    @Test
    void deleteReturnsFalseWhenMissionDoesNotExist() {
        var memory = mock(MissionMemoryService.class);
        var eventPublisher = mock(CompanyEventPublisher.class);
        when(memory.find("MISSION-404")).thenReturn(Optional.empty());

        var service = new MissionService(memory, mock(MissionExecutor.class), eventPublisher, teamMemory);

        assertFalse(service.delete("MISSION-404"));
        verify(memory, never()).deleteMission(anyString());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void deleteRejectsMissionThatIsStillRunning() {
        var memory = mock(MissionMemoryService.class);
        when(memory.find("MISSION-42")).thenReturn(Optional.of(missionIn("MISSION-42", MissionStatus.WAITING_AGENT_RESULTS)));

        var service = new MissionService(memory, mock(MissionExecutor.class), mock(CompanyEventPublisher.class), teamMemory);

        assertThrows(IllegalStateException.class, () -> service.delete("MISSION-42"));
        verify(memory, never()).deleteMission(anyString());
    }

    @Test
    void deleteRejectsFoundationalMission() {
        var memory = mock(MissionMemoryService.class);
        when(memory.find("MISSION-001")).thenReturn(Optional.of(missionIn("MISSION-001", MissionStatus.COMPLETED)));

        var service = new MissionService(memory, mock(MissionExecutor.class), mock(CompanyEventPublisher.class), teamMemory);

        assertThrows(IllegalStateException.class, () -> service.delete("MISSION-001"));
        verify(memory, never()).deleteMission(anyString());
    }

    @Test
    void deleteRejectsMissionWithRealCustomersOrTransactions() {
        var memory = mock(MissionMemoryService.class);
        when(memory.find("MISSION-42")).thenReturn(Optional.of(missionIn("MISSION-42", MissionStatus.AWAITING_INVESTOR)));
        when(memory.hasRealCustomerData("MISSION-42")).thenReturn(true);

        var service = new MissionService(memory, mock(MissionExecutor.class), mock(CompanyEventPublisher.class), teamMemory);

        assertThrows(IllegalStateException.class, () -> service.delete("MISSION-42"));
        verify(memory, never()).deleteMission(anyString());
    }

    @Test
    void deleteRemovesFinishedMissionAndPublishesEvent() {
        var memory = mock(MissionMemoryService.class);
        var eventPublisher = mock(CompanyEventPublisher.class);
        when(memory.find("MISSION-42")).thenReturn(Optional.of(missionIn("MISSION-42", MissionStatus.FAILED)));
        when(memory.hasRealCustomerData("MISSION-42")).thenReturn(false);

        var service = new MissionService(memory, mock(MissionExecutor.class), eventPublisher, teamMemory);

        assertTrue(service.delete("MISSION-42"));
        verify(memory).deleteMission("MISSION-42");
        verify(eventPublisher).publish(
                eq("EMPRESA_MISSION_DELETED"), eq("MISSION-42"), any(), eq("human"), any()
        );
    }

    private static TeamSnapshot activeEngineering() {
        return new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "Cloud Architect", "CLOUD_ARCHITECT_LEAD_BACKEND",
                        List.of("arquitectura backend"), "qwen3:8b")));
    }

    @Test
    void startRejectsAnUnknownTeamIdWithoutPersistingTheMission() {
        var memory = mock(MissionMemoryService.class);
        var service = new MissionService(memory, mock(MissionExecutor.class), mock(CompanyEventPublisher.class), teamMemory);

        assertThrows(IllegalArgumentException.class,
                () -> service.start("MISSION-7", "Crear un juego", "PRODUCTION", null, "TEAM-INVENTADO"));
        verify(memory, never()).ensureMission(any(), any(), any(), any(), any());
    }

    @Test
    void startRejectsATeamThatIsNotActive() {
        var memory = mock(MissionMemoryService.class);
        when(teamMemory.snapshot("TEAM-ENGINEERING")).thenReturn(
                new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "INACTIVE", "engineering",
                        activeEngineering().members()));
        var service = new MissionService(memory, mock(MissionExecutor.class), mock(CompanyEventPublisher.class), teamMemory);

        assertThrows(IllegalArgumentException.class,
                () -> service.start("MISSION-7", "Crear un juego", "PRODUCTION", null, "TEAM-ENGINEERING"));
        verify(memory, never()).ensureMission(any(), any(), any(), any(), any());
    }

    @Test
    void startPersistsAValidActiveTeamId() {
        var memory = mock(MissionMemoryService.class);
        var executor = mock(MissionExecutor.class);
        when(executor.executeAsync(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(teamMemory.snapshot("TEAM-ENGINEERING")).thenReturn(activeEngineering());
        var mission = new MissionResponse("MISSION-7", MissionStatus.CREATED, "PRODUCTION", 0, "Creada",
                "Misión recibida", Instant.now(), null, "TEAM-ENGINEERING");
        when(memory.find("MISSION-7")).thenReturn(Optional.of(mission));
        var service = new MissionService(memory, executor, mock(CompanyEventPublisher.class), teamMemory);

        var response = service.start("MISSION-7", "Crear un juego", "PRODUCTION", null, "TEAM-ENGINEERING");

        verify(memory).ensureMission("MISSION-7", "Crear un juego", "PRODUCTION", null, "TEAM-ENGINEERING");
        assertEquals("TEAM-ENGINEERING", response.teamId());
    }
}

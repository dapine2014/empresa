package com.aicompany.core.prospecting;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.AlertMailService;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec búsqueda de prospectos §2: Kira propone, el fundador decide (🔴). */
class StrategyProposalServiceTest {

    private final ProspectingMemoryService memory = mock(ProspectingMemoryService.class);
    private final CeoService ceo = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final AlertMailService mail = mock(AlertMailService.class);
    private final StrategyProposalService service = new StrategyProposalService(memory, ceo, companyMemory, events, mail,
            "qwen3:8b");

    {
        when(memory.strategies()).thenReturn(List.of());
        when(memory.stats()).thenReturn(List.of());
        when(companyMemory.agentModel(anyString(), anyString())).thenReturn("nvidia-creative:m");
    }

    private static StoredStrategy stored(String id, String name, String status) {
        return new StoredStrategy(id, name, "d", "h", status, "growth-content", Instant.now(), null);
    }

    @Test
    void aValidProposalIsSavedAsPendingAndAnnounced() {
        when(ceo.proposeProspectingStrategy(anyString(), anyString()))
                .thenReturn(new StrategyProposal("Podcasts del nicho", "Invitados de podcasts", "podcast"));

        var saved = service.proposeWeekly().orElseThrow();

        assertEquals("PENDING_APPROVAL", saved.status());
        verify(memory).saveStrategy(argThat(s -> "Podcasts del nicho".equals(s.name())));
        verify(events).publish(eq("EMPRESA_PROSPECTING_STRATEGY_PROPOSED"), isNull(), isNull(), eq("growth-content"), anyMap());
        verify(mail).send(contains("estrategia"), contains("Podcasts del nicho"), eq(false));
    }

    @Test
    void withAPendingProposalNoOtherIsRequested() {
        when(memory.strategies()).thenReturn(List.of(stored("S1", "Eventos", "PENDING_APPROVAL")));

        assertTrue(service.proposeWeekly().isEmpty());
        verifyNoInteractions(ceo);
    }

    @Test
    void aRepeatedNameIsNotSavedEvenIfItWasRejectedOrIsBase() {
        when(memory.strategies()).thenReturn(List.of(stored("S1", "Eventos", "REJECTED")));
        when(ceo.proposeProspectingStrategy(anyString(), anyString()))
                .thenReturn(new StrategyProposal("eventos", "otra vez", "x"))
                .thenReturn(new StrategyProposal("Directorios del rubro", "base", "x"));

        assertTrue(service.proposeWeekly().isEmpty());
        assertTrue(service.proposeWeekly().isEmpty());
        verify(memory, never()).saveStrategy(any());
    }

    @Test
    void aModelFailureDoesNotPropagate() {
        when(ceo.proposeProspectingStrategy(anyString(), anyString())).thenThrow(new IllegalStateException("caído"));

        assertTrue(service.proposeWeekly().isEmpty());
    }

    @Test
    void approvingAPendingStrategy() {
        when(memory.strategy("S1")).thenReturn(Optional.of(stored("S1", "Eventos", "PENDING_APPROVAL")));

        service.approve("S1");

        verify(memory).decideStrategy(eq("S1"), eq("APPROVED"), any());
        verify(events).publish(eq("EMPRESA_PROSPECTING_STRATEGY_APPROVED"), isNull(), isNull(), eq("human"), anyMap());
    }

    @Test
    void decidingANonPendingStrategyIsRejected() {
        when(memory.strategy("S1")).thenReturn(Optional.of(stored("S1", "Eventos", "APPROVED")));

        assertThrows(IllegalArgumentException.class, () -> service.reject("S1"));
        verify(memory, never()).decideStrategy(anyString(), anyString(), any());
    }

    @Test
    void viewsIncludeBaseAndStoredWithPerformance() {
        when(memory.strategies()).thenReturn(List.of(stored("S1", "Eventos", "APPROVED")));
        when(memory.stats()).thenReturn(List.of(
                new RunStat("P1", "BASE-DIRECTORIES", 4, Instant.now()),
                new RunStat("P2", "BASE-DIRECTORIES", 2, Instant.now())));

        var views = service.views();

        assertEquals(5, views.size());
        var directories = views.stream().filter(v -> v.id().equals("BASE-DIRECTORIES")).findFirst().orElseThrow();
        assertEquals(2, directories.runs());
        assertEquals(3.0, directories.validPerRun());
    }

    @Test
    void findByNamePrefersTheExactPendingName() {
        when(memory.strategies()).thenReturn(List.of(stored("S1", "Podcasts", "PENDING_APPROVAL"),
                stored("S2", "Podcasts del nicho", "PENDING_APPROVAL"), stored("S3", "Eventos", "APPROVED")));

        assertEquals(List.of("S1"), service.findByName("podcasts").stream().map(StoredStrategy::id).toList());
        assertEquals(List.of("S2"), service.findByName("del nicho").stream().map(StoredStrategy::id).toList());
        assertTrue(service.findByName("eventos").isEmpty());
    }

    // Decisión del fundador (2026-10-01): con Kira apagada no hay propuesta semanal ni llamada al modelo.
    @Test
    void noWeeklyProposalWhileKiraIsTurnedOff() {
        var availability = mock(com.aicompany.core.service.AgentAvailability.class);
        when(availability.isEnabled("growth-content")).thenReturn(false);
        service.setAgentAvailability(availability);

        assertTrue(service.proposeWeekly().isEmpty());
        verifyNoInteractions(ceo);
    }
}

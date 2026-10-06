package com.aicompany.core.recovery;

import com.aicompany.core.agent.model.StrategyProposal;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import com.aicompany.core.evidence.WebSearchPort;
import com.aicompany.core.evidence.WebSearchResult;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.PromptMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec misiones durables §3 (revisión 2026-10-06): búsqueda web + estrategia que cita una página leída y no se repite. */
class CorrectionStrategistTest {

    private static final String ERROR = "error CS0246: The type or namespace name 'Foo' could not be found";
    private static final String U1 = "https://learn.microsoft.com/cs0246";
    private static final String U2 = "https://stackoverflow.com/q/1";

    private final WebSearchPort search = mock(WebSearchPort.class);
    private final EvidenceAcquisitionService pages = mock(EvidenceAcquisitionService.class);
    private final CeoService ceo = mock(CeoService.class);
    private final RecoveryMemoryService recovery = mock(RecoveryMemoryService.class);
    private final CompanyMemoryService company = mock(CompanyMemoryService.class);
    private final PromptMemoryService prompts = mock(PromptMemoryService.class);
    private final CorrectionStrategist strategist =
            new CorrectionStrategist(search, pages, ceo, recovery, company, prompts, "qwen");

    private static CorrectionReport report(String... failures) {
        return new CorrectionReport("t", failures.length, List.of(failures));
    }

    private void twoPages() {
        when(search.search(anyString(), any(), any(), anyInt())).thenReturn(List.of(
                new WebSearchResult("a", U1, "d"), new WebSearchResult("b", U2, "d")));
        when(pages.fetchPage(anyString())).thenReturn("<html><body><p>Solución con using System.Text</p></body></html>");
        when(company.agentModel(anyString(), anyString())).thenReturn("m");
        when(recovery.strategies(anyString())).thenReturn(List.of());
    }

    @Test
    void picksThePersistentErrorSearchesAndAcceptsAStrategyCitingAReadPage() {
        twoPages();
        when(ceo.proposeCorrectionStrategy(anyString(), anyString(), any(), anyString()))
                .thenReturn(new StrategyProposal("Agregar el using faltante", List.of(U1)));
        var current = report("otra falla", ERROR);
        var previous = List.of(report(ERROR), report(ERROR, "x"));

        var result = strategist.propose("M1", 3, current, previous, ".NET 8");

        assertTrue(result.isPresent());
        assertEquals(CorrectionReport.fingerprint(ERROR), result.get().fingerprint());
        assertEquals(3, result.get().round());
        assertEquals(List.of(U1), result.get().sources());
        var query = ArgumentCaptor.forClass(String.class);
        verify(search).search(query.capture(), any(), any(), anyInt());
        assertTrue(query.getValue().contains("CS0246"));
        assertTrue(query.getValue().contains(".NET 8"));
        verify(pages).fetchPage(U1);
        verify(pages).fetchPage(U2);
    }

    @Test
    void anInventedSourceIsRejectedAndRetried() {
        twoPages();
        when(ceo.proposeCorrectionStrategy(anyString(), anyString(), any(), anyString()))
                .thenReturn(new StrategyProposal("Algo", List.of("https://inventada")))
                .thenReturn(new StrategyProposal("Algo bueno", List.of(U2)));

        var result = strategist.propose("M1", 2, report(ERROR), List.of(report(ERROR)), ".NET 8");

        assertTrue(result.isPresent());
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(ceo, times(2)).proposeCorrectionStrategy(anyString(), prompt.capture(), any(), anyString());
        assertFalse(prompt.getAllValues().get(0).contains("CORRECCIÓN"));
        assertTrue(prompt.getAllValues().get(1).contains("CORRECCIÓN"));
    }

    @Test
    void aRepeatedStrategyIsRejected() {
        twoPages();
        when(recovery.strategies("M1")).thenReturn(List.of(
                new CorrectionStrategy(CorrectionReport.fingerprint(ERROR), 1, "Agregar  el USING faltante", List.of(U1))));
        when(ceo.proposeCorrectionStrategy(anyString(), anyString(), any(), anyString()))
                .thenReturn(new StrategyProposal("agregar el using faltante", List.of(U1)));

        assertTrue(strategist.propose("M1", 2, report(ERROR), List.of(report(ERROR)), ".NET 8").isEmpty());
        verify(ceo, times(3)).proposeCorrectionStrategy(anyString(), anyString(), any(), anyString());
    }

    @Test
    void aSearchFailureGivesNoStrategyAndNeverThrows() {
        when(recovery.strategies(anyString())).thenReturn(List.of());
        when(search.search(anyString(), any(), any(), anyInt())).thenThrow(new IllegalStateException("caído"));

        assertTrue(strategist.propose("M1", 2, report(ERROR), List.of(), ".NET 8").isEmpty());
        verifyNoInteractions(ceo);
    }

    @Test
    void noReadablePageGivesNoStrategy() {
        when(recovery.strategies(anyString())).thenReturn(List.of());
        when(search.search(anyString(), any(), any(), anyInt())).thenReturn(List.of(new WebSearchResult("a", U1, "d")));
        when(pages.fetchPage(anyString())).thenThrow(new IllegalStateException("bloqueado")).thenReturn("");

        assertTrue(strategist.propose("M1", 2, report(ERROR), List.of(), ".NET 8").isEmpty());
        verifyNoInteractions(ceo);
    }

    @Test
    void persistentFailureFollowsTheSpecRule() {
        var a = "error A"; var b = "error B"; var c = "error C";
        assertEquals(a, CorrectionStrategist.persistentFailure(report(a, b), List.of(report(b), report(c))));
        assertEquals(b, CorrectionStrategist.persistentFailure(report(a, b), List.of(report(b), report(b, c))));
        assertEquals(a, CorrectionStrategist.persistentFailure(report(a, b), List.of(report(b))));
        assertEquals(a, CorrectionStrategist.persistentFailure(report(a, b), List.of(report(c), report(c))));
    }

    @Test
    void anExceptionInOneAttemptStillUsesTheRemainingAttempts() {
        twoPages();
        when(ceo.proposeCorrectionStrategy(anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("timeout"))
                .thenReturn(new StrategyProposal("Algo bueno", List.of(U1)));

        assertTrue(strategist.propose("M1", 2, report(ERROR), List.of(), ".NET 8").isPresent());
        verify(ceo, times(2)).proposeCorrectionStrategy(anyString(), anyString(), any(), anyString());
    }
}

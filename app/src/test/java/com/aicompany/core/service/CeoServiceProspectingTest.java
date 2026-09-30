package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import com.aicompany.core.prospecting.StrategyOption;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec búsqueda de prospectos: Sofía investiga con la herramienta; Kira propone estrategias. */
class CeoServiceProspectingTest {

    private final OpenAiCompatibleClient remote = mock(OpenAiCompatibleClient.class);
    private final EvidenceAcquisitionService evidence = mock(EvidenceAcquisitionService.class);
    private final CeoService ceoService = new CeoService(mock(RestClient.class), JsonMapper.builder().build(),
            evidence, mock(CompanyEventPublisher.class), new SimpleMeterRegistry(), Map.of("nvidia-discovery", remote));
    private final StrategyOption strategy = new StrategyOption("BASE-DIRECTORIES", "Directorios", "desc", "directorio");

    @SuppressWarnings("unchecked")
    @Test
    void theSearchUsesTheToolWithTheSheetScopeAndNeverCombinesFormatAndTools() {
        when(remote.complete(anyString(), anyList(), isNotNull(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("", List.of(Map.of("function",
                        Map.of("name", "search_web_evidence", "arguments", Map.of("query", "content agencies"))))))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("NINGUNA", List.of()));
        when(evidence.searchEvidence("content agencies", null, "en")).thenReturn(List.of());
        when(remote.complete(anyString(), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"prospects\":[{\"name\":\"Acme\","
                        + "\"url\":\"https://acme.com\",\"contactEmail\":\"a@acme.com\",\"contactFormUrl\":\"\","
                        + "\"contactSourceUrl\":\"https://acme.com/c\",\"fitReason\":\"publican mucho\"}]}", List.of()));

        var batch = ceoService.searchProspects("Producto: Pack", strategy, null, "en", "nvidia-discovery:m");

        assertEquals(1, batch.prospects().size());
        assertEquals("Acme", batch.prospects().get(0).name());
        verify(evidence).searchEvidence("content agencies", null, "en");
        verify(remote, never()).complete(anyString(), anyList(), isNotNull(), eq(true), anyInt());
    }

    @Test
    void withoutAToolCallItStillReturnsTheFinalBatch() {
        when(remote.complete(anyString(), anyList(), isNotNull(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("no necesito buscar", List.of()));
        when(remote.complete(anyString(), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"prospects\":[]}", List.of()));

        var batch = ceoService.searchProspects("Producto: Pack", strategy, "US", "en", "nvidia-discovery:m");

        assertTrue(batch.prospects().isEmpty());
        verify(evidence, never()).searchEvidence(anyString(), any(), any());
    }

    @Test
    void kiraProposesAStrategy() {
        when(remote.complete(anyString(), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"name\":\"Podcasts del nicho\","
                        + "\"description\":\"Invitados de podcasts\",\"searchHints\":\"podcast, episodio\"}", List.of()));

        var proposal = ceoService.proposeProspectingStrategy("Directorios: 2 válidos por corrida", "nvidia-discovery:m");

        assertEquals("Podcasts del nicho", proposal.name());
    }

    @Test
    void sofiaDraftsAnOutreachEmailWithoutTools() {
        var prompt = org.mockito.ArgumentCaptor.forClass(List.class);
        when(remote.complete(anyString(), prompt.capture(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"subject\":\"Firmas\",\"body\":\"Hola\"}", List.of()));

        var draft = ceoService.draftOutreach("Producto: Email Signature Generator", "Acme Studio: publican mucho",
                "Debe mencionar el precio", "nvidia-discovery:m");

        assertEquals("Firmas", draft.subject());
        assertTrue(prompt.getValue().toString().contains("CORRECCIÓN DEL INTENTO ANTERIOR"));
        verify(remote, never()).complete(anyString(), anyList(), isNotNull(), anyBoolean(), anyInt());
    }

    // Verificado en vivo (2026-09-30): con solo título y fragmento Sofía no veía emails y devolvía una lista vacía.
    @SuppressWarnings("unchecked")
    @Test
    void sofiaGetsPageTextAndContactsWithoutTheRelevanceFilter() {
        when(remote.complete(anyString(), anyList(), isNotNull(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("", List.of(Map.of("function",
                        Map.of("name", "search_web_evidence", "arguments", Map.of("query", "content agency directory"))))))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("NINGUNA", List.of()));
        when(evidence.searchEvidence("content agency directory", null, "en")).thenReturn(List.of(
                new com.aicompany.core.evidence.EvidenceCandidate("content agency directory", "https://list.com/top",
                        "Top agencies", "snippet", "WEB")));
        when(evidence.fetchPage("https://list.com/top"))
                .thenReturn("<p>Acme Studio helps founders. Write to hello@acme.com</p><a href=\"/contact\">Contact</a>");
        var finalMessages = org.mockito.ArgumentCaptor.forClass(List.class);
        when(remote.complete(anyString(), finalMessages.capture(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"prospects\":[]}", List.of()));

        ceoService.searchProspects("Producto: Pack", strategy, null, "en", "nvidia-discovery:m");

        var sent = finalMessages.getValue().toString();
        assertTrue(sent.contains("Acme Studio helps founders"), sent);
        assertTrue(sent.contains("hello@acme.com"), sent);
        assertTrue(sent.contains("https://list.com/contact"), sent);
        verify(evidence, never()).confirmReachable(any());
    }

    @Test
    void atMostTwoSearchesPerRunAndTheQueryLanguageComesFromTheSheet() {
        var decision = org.mockito.ArgumentCaptor.forClass(List.class);
        when(remote.complete(anyString(), decision.capture(), isNotNull(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("", List.of(Map.of("function",
                        Map.of("name", "search_web_evidence", "arguments", Map.of("query", "q"))))));
        when(evidence.searchEvidence(anyString(), any(), any())).thenReturn(List.of());
        when(remote.complete(anyString(), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"prospects\":[]}", List.of()));

        ceoService.searchProspects("Producto: Pack", strategy, null, "en", "nvidia-discovery:m");

        verify(evidence, times(2)).searchEvidence(anyString(), any(), any());
        assertTrue(decision.getAllValues().get(0).toString().contains("idioma \"en\""), decision.getAllValues().get(0).toString());
    }
}

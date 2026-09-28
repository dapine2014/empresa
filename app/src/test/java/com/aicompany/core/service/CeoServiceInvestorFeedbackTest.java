package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec evidence-rounds §5 (revisión 2026-09-27): el CEO reparte el pedido; Java garantiza que nadie quede sin él. */
class CeoServiceInvestorFeedbackTest {

    private final OpenAiCompatibleClient ceo = mock(OpenAiCompatibleClient.class);
    private final CeoService ceoService = new CeoService(mock(RestClient.class), JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class), mock(CompanyEventPublisher.class), new SimpleMeterRegistry(),
            Map.of("nvidia-ceo", ceo));

    @Test
    void eachAgentGetsItsPartOfTheRequest() {
        when(ceo.complete(anyString(), anyList(), isNull(), eq(true), anyInt())).thenReturn(new OpenAiCompatibleClient.RemoteReply(
                "{\"sales\":\"Busca 3 precios reales\",\"finance\":\"Recalcula con esos precios\"}", List.of()));

        var routed = ceoService.routeInvestorFeedback("Buscar servicio", "resultados", "Quiero precios reales",
                List.of("sales", "finance"), "nvidia-ceo:m");

        assertTrue(routed.get("sales").startsWith("Busca 3 precios reales"), routed.get("sales"));
        assertTrue(routed.get("finance").startsWith("Recalcula con esos precios"), routed.get("finance"));
        assertTrue(routed.get("sales").contains("Quiero precios reales"), routed.get("sales"));
    }

    @Test
    void anAgentLeftEmptyOrAFailingModelFallsBackToTheFullRequest() {
        when(ceo.complete(anyString(), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"sales\":\"\"}", List.of()))
                .thenThrow(new IllegalStateException("429"));

        var partial = ceoService.routeInvestorFeedback("i", "r", "Quiero precios reales", List.of("sales", "qa"), "nvidia-ceo:m");
        var failed = ceoService.routeInvestorFeedback("i", "r", "Quiero precios reales", List.of("sales"), "nvidia-ceo:m");

        assertEquals("Quiero precios reales", partial.get("sales"));
        assertEquals("Quiero precios reales", partial.get("qa"));
        assertEquals("Quiero precios reales", failed.get("sales"));
    }

    @Test
    void withoutACommentTheRoundStillRuns() {
        var routed = ceoService.routeInvestorFeedback("i", "r", "  ", List.of("sales"), "nvidia-ceo:m");

        assertEquals("(sin comentario del inversionista)", routed.get("sales"));
        verifyNoInteractions(ceo);
    }
}

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

/** Spec orquestador (2026-09-28): decisiones estructuradas de Alex, siempre validadas después en Java. */
class CeoServiceOrchestratorTest {

    private final OpenAiCompatibleClient ceo = mock(OpenAiCompatibleClient.class);
    private final CeoService ceoService = new CeoService(mock(RestClient.class), JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class), mock(CompanyEventPublisher.class), new SimpleMeterRegistry(),
            Map.of("nvidia-ceo", ceo));

    private void reply(String json) {
        when(ceo.complete(anyString(), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply(json, List.of()));
    }

    @Test
    void alexChoosesAProductWithAReason() {
        reply("{\"productId\":\"P2\",\"reason\":\"Más evidencia de demanda y margen claro\"}");

        var choice = ceoService.chooseProduct("- P1: Landing\n- P2: Asesoría", "nvidia-ceo:m");

        assertEquals("P2", choice.productId());
        assertTrue(choice.reason().contains("margen"));
    }

    @Test
    void alexCompletesTheSheet() {
        reply("{\"kind\":\"SERVICE\",\"targetCustomer\":\"Pequeñas empresas\",\"markets\":[\"WORLDWIDE\"],"
                + "\"languages\":[\"en\",\"es\"],\"priceUsd\":150,\"priceOnRequest\":false,\"estimatedCostUsd\":20,"
                + "\"delivery\":\"Sesión de diagnóstico + informe\"}");

        var sheet = ceoService.proposeProductSheet("Asesoría en automatización", "Max: margen 90%", "nvidia-ceo:m");

        assertEquals("SERVICE", sheet.kind());
        assertEquals(150.0, sheet.priceUsd());
        assertEquals(List.of("WORLDWIDE"), sheet.markets());
    }

    @Test
    void alexSummarizesTheDeliveryFromCreative() {
        reply("{\"delivery\":\"1) Formulario de entrada 2) Entrega en 48 h 3) Revisión\"}");

        assertTrue(ceoService.summarizeDelivery("Asesoría", "Kael: flujo en 3 pasos", "nvidia-ceo:m").contains("48 h"));
    }

    @Test
    void anInvalidAnswerFailsClearly() {
        reply("no es json");

        assertThrows(IllegalStateException.class, () -> ceoService.chooseProduct("- P1", "nvidia-ceo:m"));
    }
}

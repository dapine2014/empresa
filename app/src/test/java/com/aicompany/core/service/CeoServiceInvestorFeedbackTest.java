package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Parseo de la respuesta de `routeInvestorFeedback` — extraído a un método
 * package-private (`parseInvestorFeedback`) para poder testearlo sin
 * mockear la cadena fluida de `RestClient`, mismo criterio que
 * `SerperSearchAdapter.parseResults` ("package-private para testear sin
 * red", ver CLAUDE.md).
 */
class CeoServiceInvestorFeedbackTest {

    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            "qwen2.5-coder:14b",
            "qwen3:8b",
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );

    @Test
    void parsesFeedbackForAllFiveAgents() {

        var json = """
                {"sales":"validar precios reales de competidores","product":"","finance":"","engineering":"","qa":""}
                """;

        var feedback = ceoService.parseInvestorFeedback(json);

        assertEquals(5, feedback.size());
        assertEquals("validar precios reales de competidores", feedback.get("sales"));
        assertEquals("", feedback.get("product"));
        assertEquals("", feedback.get("finance"));
        assertEquals("", feedback.get("engineering"));
        assertEquals("", feedback.get("qa"));
    }

    @Test
    void stripsMarkdownFencesBeforeParsing() {

        var json = """
                ```json
                {"sales":"","product":"revisar margen","finance":"","engineering":"","qa":""}
                ```
                """;

        var feedback = ceoService.parseInvestorFeedback(json);

        assertEquals("revisar margen", feedback.get("product"));
    }

    @Test
    void throwsOnMalformedJson() {

        assertThrows(Exception.class, () -> ceoService.parseInvestorFeedback("no es json"));
    }
}

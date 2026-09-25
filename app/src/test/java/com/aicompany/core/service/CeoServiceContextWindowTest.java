package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verificado en vivo: sin options.num_ctx, Ollama corre qwen3:8b con
 * KvSize 4096 y recorta en silencio prompts de código/revisión. Las
 * llamadas estructuradas de equipos piden un contexto explícito; el resto
 * (discovery, chat) no cambia.
 */
class CeoServiceContextWindowTest {

    private static final String PLAN_RESPONSE = """
            {"message": {"role": "assistant", "content":
              "{\\"summary\\":\\"s\\",\\"techStack\\":\\"\\",\\"entryPoint\\":\\"\\",\\"tasks\\":[]}"}}
            """;

    @Test
    void teamStructuredCallsRequestAnExplicitContextWindow() {
        var builder = RestClient.builder().baseUrl("http://ollama");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ollama/api/chat"))
                .andExpect(jsonPath("$.options.num_ctx").value(CeoService.TEAM_CONTEXT_WINDOW_TOKENS))
                // Verificado en vivo (MISSION-TEAM-VERIFY-7): sin tope de salida, qwen3:8b generó
                // durante más de una hora en bucle y bloqueó la misión.
                .andExpect(jsonPath("$.options.num_predict").value(CeoService.TEAM_MAX_OUTPUT_TOKENS))
                .andRespond(withSuccess(PLAN_RESPONSE, MediaType.APPLICATION_JSON));

        var ceoService = new CeoService(builder.build(), JsonMapper.builder().build(),
                mock(EvidenceAcquisitionService.class), mock(CompanyEventPublisher.class), new SimpleMeterRegistry());

        var plan = ceoService.planTeamWork("engineering", "prompt", "", "qwen3:8b");

        assertEquals("s", plan.summary());
        server.verify();
    }
}

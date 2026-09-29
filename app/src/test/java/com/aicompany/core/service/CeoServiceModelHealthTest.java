package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Spec salud de modelos (2026-09-28): un modelo remoto caído se desvía al suplente local del agente. */
class CeoServiceModelHealthTest {

    private static final String KIMI = "nvidia:moonshotai/kimi-k3";
    private static final String PLAN = """
            {"message": {"role": "assistant", "content":
              "{\\"summary\\":\\"del suplente\\",\\"techStack\\":\\"\\",\\"entryPoint\\":\\"\\",\\"tasks\\":[]}"}}
            """;

    private final OpenAiCompatibleClient nvidia = mock(OpenAiCompatibleClient.class);
    private final ModelHealthService health = mock(ModelHealthService.class);
    private final RestClient.Builder ollamaBuilder = RestClient.builder().baseUrl("http://ollama");
    private final MockRestServiceServer ollama = MockRestServiceServer.bindTo(ollamaBuilder).build();
    private final CeoService ceoService = new CeoService(ollamaBuilder.build(), JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class), mock(CompanyEventPublisher.class), new SimpleMeterRegistry(),
            Map.of("nvidia", nvidia));

    {
        ceoService.setModelHealth(health);
        when(health.fallbackFor("engineering")).thenReturn("qwen3-coder:30b");
        when(health.downSince(KIMI)).thenReturn(Instant.parse("2026-09-28T17:30:00Z"));
    }

    @Test
    void aDownModelGoesStraightToTheFallbackWithoutCallingNvidia() {
        when(health.isDown(KIMI)).thenReturn(true);
        ollama.expect(requestTo("http://ollama/api/chat")).andExpect(jsonPath("$.model").value("qwen3-coder:30b"))
                .andRespond(withSuccess(PLAN, MediaType.APPLICATION_JSON));

        var plan = ceoService.planTeamWork("engineering", "prompt", "", KIMI);

        assertEquals("del suplente", plan.summary());
        verifyNoInteractions(nvidia);
        ollama.verify();
    }

    @Test
    void theCallThatDetectsTheOutageIsRepeatedWithTheFallback() {
        when(health.isDown(KIMI)).thenReturn(false, true);
        when(nvidia.complete(anyString(), anyList(), any(), anyBoolean(), anyInt()))
                .thenThrow(new RemoteUnavailableException("Modelo remoto moonshotai/kimi-k3 no responde: timeout", null));
        ollama.expect(requestTo("http://ollama/api/chat")).andExpect(jsonPath("$.model").value("qwen3-coder:30b"))
                .andRespond(withSuccess(PLAN, MediaType.APPLICATION_JSON));

        var plan = ceoService.planTeamWork("engineering", "prompt", "", KIMI);

        assertEquals("del suplente", plan.summary());
        verify(health).recordFailure(eq(KIMI), contains("timeout"));
    }

    // Verificado en vivo (2026-09-29): un solo cuerpo ilegible de nemotron tumbó la consolidación de una misión porque
    // el modelo aún no estaba DOWN (hacen falta 2 fallos). La llamada que falla va al suplente igual.
    @Test
    void aSingleOutageAlreadyRepeatsThatCallWithTheFallback() {
        when(health.isDown(KIMI)).thenReturn(false);
        when(nvidia.complete(anyString(), anyList(), any(), anyBoolean(), anyInt()))
                .thenThrow(new RemoteUnavailableException("Modelo remoto moonshotai/kimi-k3 no responde: cuerpo ilegible", null));
        ollama.expect(requestTo("http://ollama/api/chat")).andExpect(jsonPath("$.model").value("qwen3-coder:30b"))
                .andRespond(withSuccess(PLAN, MediaType.APPLICATION_JSON));

        var plan = ceoService.planTeamWork("engineering", "prompt", "", KIMI);

        assertEquals("del suplente", plan.summary());
        verify(health).recordFailure(eq(KIMI), contains("ilegible"));
    }

    @Test
    void aSuccessfulCallIsRecordedAndNeverTouchesOllama() {
        when(health.isDown(KIMI)).thenReturn(false);
        when(nvidia.complete(anyString(), anyList(), any(), anyBoolean(), anyInt())).thenReturn(
                new OpenAiCompatibleClient.RemoteReply("{\"summary\":\"de kimi\",\"techStack\":\"\",\"entryPoint\":\"\",\"tasks\":[]}", List.of()));

        assertEquals("de kimi", ceoService.planTeamWork("engineering", "prompt", "", KIMI).summary());
        verify(health).recordSuccess(KIMI);
        ollama.verify();
    }

    @Test
    void withoutAFallbackItFailsFastWithTheReason() {
        when(health.isDown(KIMI)).thenReturn(true);
        when(health.fallbackFor("engineering")).thenReturn("");

        var ex = assertThrows(IllegalStateException.class, () -> ceoService.planTeamWork("engineering", "prompt", "", KIMI));

        assertTrue(ex.getMessage().contains("no tiene suplente"), ex.getMessage());
        verifyNoInteractions(nvidia);
    }

    @Test
    void whenTheFallbackAlsoFailsTheErrorNamesBoth() {
        when(health.isDown(KIMI)).thenReturn(true);
        ollama.expect(requestTo("http://ollama/api/chat")).andRespond(withServerError());

        var ex = assertThrows(IllegalStateException.class, () -> ceoService.planTeamWork("engineering", "prompt", "", KIMI));

        assertTrue(ex.getMessage().contains("kimi-k3") && ex.getMessage().contains("qwen3-coder:30b"), ex.getMessage());
    }

    @Test
    void aClientErrorDoesNotCountAsAnOutage() {
        when(health.isDown(KIMI)).thenReturn(false);
        when(nvidia.complete(anyString(), anyList(), any(), anyBoolean(), anyInt()))
                .thenThrow(new IllegalStateException("Modelo remoto respondió HTTP 400: bad request"));

        assertThrows(IllegalStateException.class, () -> ceoService.planTeamWork("engineering", "prompt", "", KIMI));
        verify(health, never()).recordFailure(any(), any());
    }
}

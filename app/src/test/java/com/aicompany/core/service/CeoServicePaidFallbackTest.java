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


/**
 * Decisión del fundador (2026-10-04): kimi gratis en NVIDIA primero; si NVIDIA cae, el respaldo pago (kimi en Bedrock);
 * si también falla, el suplente local.
 */
class CeoServicePaidFallbackTest {

    private static final String KIMI = "nvidia:moonshotai/kimi-k3";
    private static final String BEDROCK = "bedrock:global.moonshotai.kimi-k3";
    private static final String PLAN_JSON = "{\\\"summary\\\":\\\"del suplente\\\",\\\"techStack\\\":\\\"\\\",\\\"entryPoint\\\":\\\"\\\",\\\"tasks\\\":[]}";
    private static final String LOCAL_PLAN = "{\"message\": {\"role\": \"assistant\", \"content\": \"" + PLAN_JSON + "\"}}";
    private static final OpenAiCompatibleClient.RemoteReply BEDROCK_PLAN = new OpenAiCompatibleClient.RemoteReply(
            "{\"summary\":\"de bedrock\",\"techStack\":\"\",\"entryPoint\":\"\",\"tasks\":[]}", List.of());

    private final OpenAiCompatibleClient nvidia = mock(OpenAiCompatibleClient.class);
    private final OpenAiCompatibleClient bedrock = mock(OpenAiCompatibleClient.class);
    private final ModelHealthService health = mock(ModelHealthService.class);
    private final RestClient.Builder ollamaBuilder = RestClient.builder().baseUrl("http://ollama");
    private final MockRestServiceServer ollama = MockRestServiceServer.bindTo(ollamaBuilder).build();
    private final CeoService ceoService = new CeoService(ollamaBuilder.build(), JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class), mock(CompanyEventPublisher.class), new SimpleMeterRegistry(),
            Map.of("nvidia", nvidia, "bedrock", bedrock));

    {
        ceoService.setModelHealth(health);
        when(health.fallbackFor("engineering")).thenReturn("qwen3-coder:30b");
        when(health.paidFallbackFor("engineering")).thenReturn(BEDROCK);
    }

    @Test
    void whenNvidiaIsDownTheCallGoesToBedrockNotToTheLocalFallback() {
        when(health.isDown(KIMI)).thenReturn(true);
        when(bedrock.complete(eq("global.moonshotai.kimi-k3"), anyList(), any(), anyBoolean(), anyInt())).thenReturn(BEDROCK_PLAN);

        assertEquals("de bedrock", ceoService.planTeamWork("engineering", "prompt", "", KIMI).summary());
        verifyNoInteractions(nvidia);
        ollama.verify();
    }

    @Test
    void theCallThatDetectsTheOutageIsRepeatedOnBedrock() {
        when(nvidia.complete(anyString(), anyList(), any(), anyBoolean(), anyInt()))
                .thenThrow(new RemoteUnavailableException("kimi no responde: timeout", null));
        when(bedrock.complete(anyString(), anyList(), any(), anyBoolean(), anyInt())).thenReturn(BEDROCK_PLAN);

        assertEquals("de bedrock", ceoService.planTeamWork("engineering", "prompt", "", KIMI).summary());
        verify(health).recordFailure(eq(KIMI), contains("timeout"));
    }

    @Test
    void ifBedrockAlsoFailsTheLocalFallbackTakesOver() {
        when(health.isDown(KIMI)).thenReturn(true);
        when(bedrock.complete(anyString(), anyList(), any(), anyBoolean(), anyInt()))
                .thenThrow(new RemoteUnavailableException("bedrock no responde", null));
        ollama.expect(requestTo("http://ollama/api/chat")).andExpect(jsonPath("$.model").value("qwen3-coder:30b"))
                .andRespond(withSuccess(LOCAL_PLAN, MediaType.APPLICATION_JSON));

        assertEquals("del suplente", ceoService.planTeamWork("engineering", "prompt", "", KIMI).summary());
        verify(health).recordFailure(eq(BEDROCK), anyString());
    }

    @Test
    void aBedrockThatIsAlreadyDownIsSkipped() {
        when(health.isDown(KIMI)).thenReturn(true);
        when(health.isDown(BEDROCK)).thenReturn(true);
        ollama.expect(requestTo("http://ollama/api/chat")).andRespond(withSuccess(LOCAL_PLAN, MediaType.APPLICATION_JSON));

        assertEquals("del suplente", ceoService.planTeamWork("engineering", "prompt", "", KIMI).summary());
        verifyNoInteractions(bedrock);
    }

    @Test
    void whileNvidiaWorksBedrockIsNeverCalled() {
        when(nvidia.complete(anyString(), anyList(), any(), anyBoolean(), anyInt())).thenReturn(
                new OpenAiCompatibleClient.RemoteReply("{\"summary\":\"de kimi\",\"techStack\":\"\",\"entryPoint\":\"\",\"tasks\":[]}", List.of()));

        assertEquals("de kimi", ceoService.planTeamWork("engineering", "prompt", "", KIMI).summary());
        verifyNoInteractions(bedrock);
    }
}

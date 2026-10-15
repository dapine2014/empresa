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

/**
 * Decisión del fundador (2026-10-15): "primero validar con los modelos gratuitos y pasar automáticamente a Bedrock si
 * los modelos gratis fallan o están caídos". Si el gratis del agente cae, se prueban los demás de la cadena en orden.
 */
class CeoServiceFreeChainTest {

    private static final String KIMI = "nvidia:moonshotai/kimi-k2.6";
    private static final String GLM = "nvidia:z-ai/glm-5.3";
    private static final String NEMO = "nvidia:nvidia/nemotron-3-super-120b-a12b";
    private static final String BEDROCK = "bedrock:global.moonshotai.kimi-k3";

    private final OpenAiCompatibleClient nvidia = mock(OpenAiCompatibleClient.class);
    private final OpenAiCompatibleClient bedrock = mock(OpenAiCompatibleClient.class);
    private final ModelHealthService health = mock(ModelHealthService.class);
    private final FreeModelChainService chain = mock(FreeModelChainService.class);
    private final CeoService ceoService = new CeoService(RestClient.builder().baseUrl("http://ollama").build(),
            JsonMapper.builder().build(), mock(EvidenceAcquisitionService.class), mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry(), Map.of("nvidia", nvidia, "bedrock", bedrock));

    {
        ceoService.setModelHealth(health);
        ceoService.setFreeModelChain(chain);
        when(health.fallbackFor("engineering")).thenReturn("qwen3-coder:30b");
        when(health.paidFallbackFor("engineering")).thenReturn(BEDROCK);
        when(chain.alternativesFor(KIMI)).thenReturn(List.of(GLM, NEMO));
    }

    private static OpenAiCompatibleClient.RemoteReply plan(String summary) {
        return new OpenAiCompatibleClient.RemoteReply(
                "{\"summary\":\"" + summary + "\",\"techStack\":\"\",\"entryPoint\":\"\",\"tasks\":[]}", List.of());
    }

    private static RemoteUnavailableException unavailable(String why) {
        return new RemoteUnavailableException(why, null);
    }

    @Test
    void whenTheAgentsFreeModelFailsTheNextFreeOneAnswersAndBedrockIsNotPaid() {
        when(nvidia.complete(eq("moonshotai/kimi-k2.6"), anyList(), any(), anyBoolean(), anyInt()))
                .thenThrow(unavailable("HTTP 404 Not found for account"));
        when(nvidia.complete(eq("z-ai/glm-5.3"), anyList(), any(), anyBoolean(), anyInt())).thenReturn(plan("de glm"));

        assertEquals("de glm", ceoService.planTeamWork("engineering", "prompt", "", KIMI).summary());
        verify(health).recordFailure(eq(KIMI), contains("404"));
        verify(health).recordSuccess(GLM);
        verifyNoInteractions(bedrock);
    }

    @Test
    void aFreeModelAlreadyDownIsSkippedWithoutWaitingForIt() {
        when(health.isDown(KIMI)).thenReturn(true);
        when(health.isDown(GLM)).thenReturn(true);
        when(nvidia.complete(eq("nvidia/nemotron-3-super-120b-a12b"), anyList(), any(), anyBoolean(), anyInt()))
                .thenReturn(plan("de nemotron"));

        assertEquals("de nemotron", ceoService.planTeamWork("engineering", "prompt", "", KIMI).summary());
        verify(nvidia, never()).complete(eq("moonshotai/kimi-k2.6"), anyList(), any(), anyBoolean(), anyInt());
        verify(nvidia, never()).complete(eq("z-ai/glm-5.3"), anyList(), any(), anyBoolean(), anyInt());
        verifyNoInteractions(bedrock);
    }

    @Test
    void ifEveryFreeModelFailsTheCallGoesToBedrock() {
        when(health.isDown(KIMI)).thenReturn(true);
        when(nvidia.complete(anyString(), anyList(), any(), anyBoolean(), anyInt())).thenThrow(unavailable("timeout"));
        when(bedrock.complete(anyString(), anyList(), any(), anyBoolean(), anyInt())).thenReturn(plan("de bedrock"));

        assertEquals("de bedrock", ceoService.planTeamWork("engineering", "prompt", "", KIMI).summary());
        verify(health).recordFailure(eq(GLM), contains("timeout"));
        verify(health).recordFailure(eq(NEMO), contains("timeout"));
    }

    @Test
    void aFreeModelThatRejectsTheRequestDoesNotStopTheChain() {
        when(health.isDown(KIMI)).thenReturn(true);
        when(nvidia.complete(eq("z-ai/glm-5.3"), anyList(), any(), anyBoolean(), anyInt()))
                .thenThrow(new IllegalStateException("HTTP 400 tools not supported"));
        when(nvidia.complete(eq("nvidia/nemotron-3-super-120b-a12b"), anyList(), any(), anyBoolean(), anyInt()))
                .thenReturn(plan("de nemotron"));

        assertEquals("de nemotron", ceoService.planTeamWork("engineering", "prompt", "", KIMI).summary());
        verify(health, never()).recordFailure(eq(GLM), anyString());
        verifyNoInteractions(bedrock);
    }

    @Test
    void anAgentWhoseModelIsNotInTheChainGoesStraightToBedrock() {
        var other = "nvidia:nvidia/nemotron-3-ultra-550b-a55b";
        when(chain.alternativesFor(other)).thenReturn(List.of());
        when(health.isDown(other)).thenReturn(true);
        when(bedrock.complete(anyString(), anyList(), any(), anyBoolean(), anyInt())).thenReturn(plan("de bedrock"));

        assertEquals("de bedrock", ceoService.planTeamWork("engineering", "prompt", "", other).summary());
        verifyNoInteractions(nvidia);
    }
}

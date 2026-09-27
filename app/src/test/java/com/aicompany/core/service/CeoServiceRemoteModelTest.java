package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Decisión del fundador (2026-09-26): Agent.model "nvidia:<modelo>" va a la API de NVIDIA, el resto a Ollama. */
class CeoServiceRemoteModelTest {

    private final RestClient ollama = mock(RestClient.class);
    private final OpenAiCompatibleClient remote = mock(OpenAiCompatibleClient.class);
    private final CeoService ceoService = new CeoService(ollama, JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class), mock(CompanyEventPublisher.class), new SimpleMeterRegistry(), remote);

    @Test
    void aNvidiaPrefixedModelGoesToTheRemoteClientWithJsonMode() {
        when(remote.chat(eq("moonshotai/kimi-k3"), anyList(), eq(true), anyInt()))
                .thenReturn("{\"summary\":\"ok\",\"files\":[{\"path\":\"src/A.cs\",\"content\":\"class A {}\"}]}");

        var result = ceoService.generateDevelopmentArtifact("backend", "prompt", "", "nvidia:moonshotai/kimi-k3");

        assertEquals("src/A.cs", result.files().get(0).path());
        verify(remote).chat(eq("moonshotai/kimi-k3"), argThat(messages -> messages.size() == 2), eq(true),
                eq(CeoService.REMOTE_TEAM_MAX_OUTPUT_TOKENS));
        verifyNoInteractions(ollama);
    }

    @Test
    void aLocalModelNeverTouchesTheRemoteClient() {
        assertThrows(IllegalStateException.class,
                () -> ceoService.generateDevelopmentArtifact("backend", "prompt", "", "qwen3:8b"));
        verifyNoInteractions(remote);
    }

    @Test
    void theRemoteProviderRejectsToolCallsExplicitly() {
        var ex = assertThrows(IllegalArgumentException.class, () -> ceoService.rejectToolsForRemoteModels(
                "AGENT_TOOL_DECISION", "nvidia:moonshotai/kimi-k3", List.of(java.util.Map.of("type", "function"))));
        assertTrue(ex.getMessage().contains("tools"), ex.getMessage());
    }
}

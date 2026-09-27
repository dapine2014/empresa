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
    private final OpenAiCompatibleClient ceoRemote = mock(OpenAiCompatibleClient.class);
    private final CeoService ceoService = new CeoService(ollama, JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class), mock(CompanyEventPublisher.class), new SimpleMeterRegistry(),
            java.util.Map.of("nvidia", remote, "nvidia-ceo", ceoRemote));

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


    // Decisión del fundador (2026-09-27): una key por grupo; el prefijo del modelo elige el proveedor.
    @Test
    void eachProviderPrefixUsesItsOwnClient() {
        when(ceoRemote.chat(eq("nvidia/nemotron-3-ultra-550b-a55b"), anyList(), eq(true), anyInt()))
                .thenReturn("no es un plan");
        assertThrows(IllegalStateException.class, () ->
                ceoService.planTeamWork("ceo", "p", "", "nvidia-ceo:nvidia/nemotron-3-ultra-550b-a55b"));
        verify(ceoRemote).chat(eq("nvidia/nemotron-3-ultra-550b-a55b"), anyList(), eq(true), anyInt());
        verifyNoInteractions(remote);
    }

    @Test
    void anUnknownRemoteProviderFailsClearlyInsteadOfFallingBackToOllama() {
        var ex = assertThrows(IllegalStateException.class, () ->
                ceoService.generateDevelopmentArtifact("backend", "p", "", "nvidia-otro:x/y"));
        assertTrue(ex.getMessage().contains("nvidia-otro"), ex.getMessage());
        verifyNoInteractions(ollama);
    }

    @Test
    void remoteModelParsing() {
        assertEquals(java.util.Optional.of(new CeoService.RemoteModel("nvidia-discovery", "nvidia/nemotron-3-super-120b-a12b")),
                CeoService.remoteModel("nvidia-discovery:nvidia/nemotron-3-super-120b-a12b"));
        assertEquals(java.util.Optional.empty(), CeoService.remoteModel("qwen3:8b"));
    }
}

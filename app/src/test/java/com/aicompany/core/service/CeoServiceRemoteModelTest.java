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

    // Subproyecto 2 (2026-09-28): el modelo se edita desde el Command Center; un proveedor remoto desconocido se
    // rechaza al guardar (antes fallaba recién cuando el agente trabajaba).
    @Test
    void anUnknownRemoteProviderIsRejectedWhenSavingTheModel() {
        var ex = assertThrows(IllegalArgumentException.class, () -> ceoService.checkModel("anthropic:claude/opus"));
        assertTrue(ex.getMessage().contains("nvidia-ceo"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ceoService.checkModel("  "));
    }

    @Test
    void knownProvidersAndLocalOllamaModelsAreAccepted() {
        assertDoesNotThrow(() -> ceoService.checkModel("nvidia-ceo:nvidia/nemotron-3-ultra-550b-a55b"));
        assertDoesNotThrow(() -> ceoService.checkModel("nvidia:moonshotai/kimi-k3"));
        assertDoesNotThrow(() -> ceoService.checkModel("qwen3:8b"));
        assertDoesNotThrow(() -> ceoService.checkModel("hf.co/user/model:Q4"));
    }

    @Test
    void aNvidiaPrefixedModelGoesToTheRemoteClientWithJsonMode() {
        when(remote.complete(eq("moonshotai/kimi-k3"), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply(
                        "{\"summary\":\"ok\",\"files\":[{\"path\":\"src/A.cs\",\"content\":\"class A {}\"}]}", List.of()));

        var result = ceoService.generateDevelopmentArtifact("backend", "prompt", "", "nvidia:moonshotai/kimi-k3");

        assertEquals("src/A.cs", result.files().get(0).path());
        verify(remote).complete(eq("moonshotai/kimi-k3"), argThat(messages -> messages.size() == 2), isNull(), eq(true),
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
        when(ceoRemote.complete(eq("nvidia/nemotron-3-ultra-550b-a55b"), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("no es un plan", List.of()));
        assertThrows(IllegalStateException.class, () ->
                ceoService.planTeamWork("ceo", "p", "", "nvidia-ceo:nvidia/nemotron-3-ultra-550b-a55b"));
        verify(ceoRemote).complete(eq("nvidia/nemotron-3-ultra-550b-a55b"), anyList(), isNull(), eq(true), anyInt());
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

    // Spec 2026-09-27 §2: el chat de Alex en remoto usa query_company_memory igual que con Ollama.
    @Test
    void aRemoteChatCanUseTheCompanyMemoryTool() {
        when(ceoRemote.complete(eq("m"), anyList(), notNull(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("", List.of(java.util.Map.of("function",
                        java.util.Map.of("name", "query_company_memory", "arguments", java.util.Map.of("topic", "AGENT_STATUS"))))));
        when(ceoRemote.complete(eq("m"), anyList(), isNull(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("respuesta con datos reales", List.of()));
        var topics = new java.util.ArrayList<String>();

        var answer = ceoService.chat("Alex", "roster", List.of(), "¿quién trabaja?",
                topic -> { topics.add(topic); return "datos"; }, "", "nvidia-ceo:m");

        assertEquals("respuesta con datos reales", answer);
        assertEquals(List.of("AGENT_STATUS"), topics);
        verifyNoInteractions(ollama);
    }
}

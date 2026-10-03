package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec salud de modelos (2026-09-28): detectar la caída, avisar una vez y detectar la vuelta sola. */
class ModelHealthServiceTest {

    private static final String KIMI = "nvidia:moonshotai/kimi-k3";

    private final OpenAiCompatibleClient nvidia = mock(OpenAiCompatibleClient.class);
    private final CompanyMemoryService memory = mock(CompanyMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final AlertMailService mail = mock(AlertMailService.class);
    private final ModelHealthService health = new ModelHealthService(Map.of("nvidia", nvidia), memory, events, mail);

    {
        when(memory.agentIdsUsingModel(KIMI)).thenReturn(List.of("engineering", "qa"));
        when(memory.agentFallbackModel(anyString())).thenReturn("qwen3-coder:30b");
    }

    @Test
    void twoAvailabilityFailuresInARowMarkTheModelDownOnce() {
        health.recordFailure(KIMI, "HTTP 504");
        assertFalse(health.isDown(KIMI));

        health.recordFailure(KIMI, "timeout");
        health.recordFailure(KIMI, "timeout");

        assertTrue(health.isDown(KIMI));
        verify(events, times(1)).publish(eq("EMPRESA_MODEL_DOWN"), isNull(), isNull(), eq("system"),
                argThat(m -> KIMI.equals(m.get("model"))));
        verify(mail, times(1)).send(contains("kimi-k3"), argThat(b -> b.contains("engineering") && b.contains("qwen3-coder:30b")),
                eq(true));
    }

    @Test
    void aSuccessResetsTheCount() {
        health.recordFailure(KIMI, "x");
        health.recordSuccess(KIMI);
        health.recordFailure(KIMI, "x");

        assertFalse(health.isDown(KIMI));
        verifyNoInteractions(mail);
    }

    @Test
    void theProbeBringsAModelBackAndTellsTheFounder() {
        health.recordFailure(KIMI, "x");
        health.recordFailure(KIMI, "x");
        when(nvidia.ping("moonshotai/kimi-k3")).thenReturn(false, true);

        health.probeDownModels();
        assertTrue(health.isDown(KIMI));

        health.probeDownModels();
        assertFalse(health.isDown(KIMI));
        verify(events).publish(eq("EMPRESA_MODEL_UP"), isNull(), isNull(), eq("system"), anyMap());
        verify(mail).send(contains("volvió"), anyString(), eq(false));
    }

    @Test
    void onlyDownModelsAreProbed() {
        health.recordFailure(KIMI, "x");

        health.probeDownModels();

        verify(nvidia, never()).ping(anyString());
    }

    @Test
    void theSnapshotShowsSinceAndAffectedAgents() {
        when(memory.remoteModelsInUse()).thenReturn(List.of(KIMI, "nvidia-ceo:nvidia/nemotron-3-ultra-550b-a55b"));
        health.recordFailure(KIMI, "x");
        health.recordFailure(KIMI, "HTTP 504");

        var snapshot = health.snapshot();

        var kimi = snapshot.stream().filter(h -> h.model().equals(KIMI)).findFirst().orElseThrow();
        assertEquals("DOWN", kimi.status());
        assertNotNull(kimi.since());
        assertEquals(List.of("engineering", "qa"), kimi.affectedAgents());
        assertTrue(snapshot.stream().anyMatch(h -> h.status().equals("UP")));
    }

    @Test
    void theFallbackComesFromTheAgent() {
        when(memory.agentFallbackModel("engineering")).thenReturn("");

        assertEquals("", health.fallbackFor("engineering"));
    }

    // ---- Suplentes (decisión del fundador 2026-10-03): un suplente que no responde no es respaldo. ----

    private void agentsWithFallback() {
        when(memory.agents()).thenReturn(List.of(
                Map.of("id", "qa", "fallbackModel", "qwen3-coder:30b", "enabled", true),
                Map.of("id", "ceo", "fallbackModel", "qwen3-coder:30b", "enabled", true)));
    }

    // Verificado en vivo (2026-10-02): Ollama quedó caído 10 horas tras un reinicio y nadie lo notó.
    @Test
    void anUnreachableOllamaMarksTheFallbackDownOnceAndTellsTheFounder() {
        agentsWithFallback();
        health.setLocalModelProbe(java.util.Optional::empty);

        health.probeFallbacks();
        assertFalse(health.isFallbackDown("qwen3-coder:30b"));
        health.probeFallbacks();
        health.probeFallbacks();

        assertTrue(health.isFallbackDown("qwen3-coder:30b"));
        verify(events, times(1)).publish(eq("EMPRESA_FALLBACK_DOWN"), isNull(), isNull(), eq("system"),
                argThat(m -> "qwen3-coder:30b".equals(m.get("model"))));
        verify(mail, times(1)).send(contains("suplente"), argThat(b -> b.contains("Ollama no responde")
                && b.contains("qa")), eq(true));
    }

    @Test
    void aFallbackThatComesBackIsAnnounced() {
        agentsWithFallback();
        health.setLocalModelProbe(java.util.Optional::empty);
        health.probeFallbacks();
        health.probeFallbacks();

        health.setLocalModelProbe(() -> java.util.Optional.of(java.util.Set.of("qwen3-coder:30b")));
        health.probeFallbacks();

        assertFalse(health.isFallbackDown("qwen3-coder:30b"));
        verify(events).publish(eq("EMPRESA_FALLBACK_UP"), isNull(), isNull(), eq("system"), anyMap());
    }

    @Test
    void aFallbackNotInstalledInOllamaIsReportedAsSuch() {
        agentsWithFallback();
        health.setLocalModelProbe(() -> java.util.Optional.of(java.util.Set.of("qwen3:8b")));
        health.probeFallbacks();
        health.probeFallbacks();

        assertTrue(health.isFallbackDown("qwen3-coder:30b"));
        var fallbacks = health.fallbackSnapshot();
        assertEquals(1, fallbacks.size());
        assertEquals("DOWN", fallbacks.get(0).status());
        assertTrue(fallbacks.get(0).lastError().contains("no está instalado"), fallbacks.get(0).lastError());
        assertEquals(List.of("ceo", "qa"), fallbacks.get(0).affectedAgents());
    }
}

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

    // Decisión del fundador (2026-10-04): el gratis; si cae, el respaldo pago; si también cae, el suplente local.
    @Test
    void theEffectiveModelFollowsTheChain() {
        var bedrock = "bedrock:global.moonshotai.kimi-k3";
        when(memory.agentPaidFallbackModel("engineering")).thenReturn(bedrock);
        assertEquals(KIMI, health.effectiveModel("engineering", KIMI));

        health.recordFailure(KIMI, "timeout");
        health.recordFailure(KIMI, "timeout");
        assertEquals(bedrock, health.effectiveModel("engineering", KIMI));

        health.recordFailure(bedrock, "timeout");
        health.recordFailure(bedrock, "timeout");
        assertEquals("qwen3-coder:30b", health.effectiveModel("engineering", KIMI));
    }
    // Cadena de modelos gratis (2026-10-15): validar antes con un ping corto, y el gratis siguiente atiende antes del pago.
    private static final String K26 = "nvidia:moonshotai/kimi-k2.6";
    private static final String GLM = "nvidia:z-ai/glm-5.3";

    private FreeModelChainService chainOf(String... models) {
        var chain = mock(FreeModelChainService.class);
        when(chain.models()).thenReturn(List.of(models));
        when(chain.alternativesFor(anyString())).thenAnswer(inv -> List.of(models).contains(inv.<String>getArgument(0))
                ? List.of(models).stream().filter(m -> !m.equals(inv.getArgument(0))).toList() : List.of());
        health.setFreeModelChain(chain);
        return chain;
    }

    @Test
    void theQuickCheckMarksAFreeModelThatDoesNotAnswerDownAfterTwoRounds() {
        chainOf(K26, GLM);
        when(nvidia.ping("moonshotai/kimi-k2.6")).thenReturn(true);
        when(nvidia.ping("z-ai/glm-5.3")).thenReturn(false);

        health.probeFreeChain();
        assertFalse(health.isDown(GLM));
        health.probeFreeChain();

        assertTrue(health.isDown(GLM));
        assertFalse(health.isDown(K26));
    }

    @Test
    void theQuickCheckDoesNotPingAModelAlreadyDown() {
        chainOf(K26);
        health.recordFailure(K26, "x");
        health.recordFailure(K26, "x");

        health.probeFreeChain();

        verify(nvidia, never()).ping(anyString());
    }

    @Test
    void withTheAgentsModelDownTheNextFreeModelServesItBeforeThePaidOne() {
        chainOf(K26, GLM);
        when(memory.agentPaidFallbackModel("engineering")).thenReturn("bedrock:global.moonshotai.kimi-k3");
        health.recordFailure(K26, "x");
        health.recordFailure(K26, "x");

        assertEquals(GLM, health.effectiveModel("engineering", K26));

        health.recordFailure(GLM, "x");
        health.recordFailure(GLM, "x");
        assertEquals("bedrock:global.moonshotai.kimi-k3", health.effectiveModel("engineering", K26));
    }

    @Test
    void theChainSnapshotKeepsItsOrderWithStatusAndAgents() {
        chainOf(GLM, K26);
        when(memory.agentIdsUsingModel(K26)).thenReturn(List.of("backend"));
        health.recordFailure(GLM, "timeout");
        health.recordFailure(GLM, "timeout");

        var snapshot = health.freeChainSnapshot();

        assertEquals(List.of(GLM, K26), snapshot.stream().map(h -> h.model()).toList());
        assertEquals("DOWN", snapshot.get(0).status());
        assertEquals(List.of("backend"), snapshot.get(1).affectedAgents());
    }
    // Verificado en vivo (Pong, 2026-10-15): glm-5.3 respondía el ping pero daba 504 en las llamadas reales; el ping
    // exitoso borraba los fallos y lo devolvía en minutos, y cada llamada de Kael perdía ~16 min con él.
    @Test
    void aSuccessfulQuickCheckDoesNotEraseTheFailuresOfRealCalls() {
        chainOf(GLM);
        when(nvidia.ping("z-ai/glm-5.3")).thenReturn(true);
        health.recordFailure(GLM, "HTTP 504");

        health.probeFreeChain();
        health.recordFailure(GLM, "HTTP 504");

        assertTrue(health.isDown(GLM));
    }

    @Test
    void aModelBroughtBackByThePingIsOnProbationAndOneRealFailureTakesItDownAgain() {
        health.recordFailure(KIMI, "x");
        health.recordFailure(KIMI, "x");
        when(nvidia.ping("moonshotai/kimi-k3")).thenReturn(true);
        health.probeDownModels();
        assertFalse(health.isDown(KIMI));

        health.recordFailure(KIMI, "HTTP 504");

        assertTrue(health.isDown(KIMI));
    }

    @Test
    void aRealSuccessEndsTheProbation() {
        health.recordFailure(KIMI, "x");
        health.recordFailure(KIMI, "x");
        when(nvidia.ping("moonshotai/kimi-k3")).thenReturn(true);
        health.probeDownModels();

        health.recordSuccess(KIMI);
        health.recordFailure(KIMI, "HTTP 504");

        assertFalse(health.isDown(KIMI));
    }
}

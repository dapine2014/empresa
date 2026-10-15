package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Cadena de modelos gratis (2026-10-15): la semilla siembra, lo guardado en Settings manda. */
class FreeModelChainServiceTest {

    private static final String KIMI = "nvidia:moonshotai/kimi-k2.6";
    private static final String GLM = "nvidia:z-ai/glm-5.3";
    private static final String NEMO = "nvidia:nvidia/nemotron-3-super-120b-a12b";

    private final FreeModelChainMemoryService memory = mock(FreeModelChainMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final Map<String, OpenAiCompatibleClient> remotes = Map.of("nvidia", mock(OpenAiCompatibleClient.class),
            "bedrock", mock(OpenAiCompatibleClient.class));
    private final FreeModelChainService service = new FreeModelChainService(memory, remotes, events,
            " " + KIMI + ", " + GLM + ",," + NEMO + "," + GLM);

    @Test
    void withoutASavedChainTheSeedIsUsedInOrderWithoutDuplicates() {
        when(memory.load()).thenReturn(Optional.empty());

        assertEquals(List.of(KIMI, GLM, NEMO), service.models());
    }

    @Test
    void theSavedChainWinsOverTheSeed() {
        when(memory.load()).thenReturn(Optional.of(new FreeModelChainMemoryService.StoredChain(List.of(NEMO), Instant.EPOCH, "dapine")));

        assertEquals(List.of(NEMO), service.models());
        assertEquals("dapine", service.chain().updatedBy());
    }

    @Test
    void ifNeo4jFailsTheSeedKeepsTheCallsGoing() {
        when(memory.load()).thenThrow(new IllegalStateException("neo4j down"));

        assertEquals(List.of(KIMI, GLM, NEMO), service.models());
    }

    @Test
    void theAlternativesAreTheOtherModelsInOrderOnlyForAModelInTheChain() {
        when(memory.load()).thenReturn(Optional.empty());

        assertEquals(List.of(KIMI, NEMO), service.alternativesFor(GLM));
        assertEquals(List.of(), service.alternativesFor("nvidia-ceo:nvidia/nemotron-3-ultra-550b-a55b"));
    }

    @Test
    void updateSavesTheCleanListAndPublishesTheChange() {
        when(memory.load()).thenReturn(Optional.empty());

        service.update(List.of(" " + GLM + " ", "", KIMI, GLM), "dapine@gmail.com");

        verify(memory).save(List.of(GLM, KIMI), "dapine@gmail.com");
        verify(events).publish(eq("EMPRESA_FREE_MODEL_CHAIN_UPDATED"), isNull(), isNull(), eq("dapine@gmail.com"),
                eq(Map.of("models", List.of(GLM, KIMI))));
    }

    @Test
    void updateRejectsLocalUnknownAndPaidModels() {
        assertThrows(IllegalArgumentException.class, () -> service.update(List.of("qwen3-coder:30b"), "x"));
        assertThrows(IllegalArgumentException.class, () -> service.update(List.of("nvidia-foo:a/b"), "x"));
        var paid = assertThrows(IllegalArgumentException.class,
                () -> service.update(List.of("bedrock:global.moonshotai.kimi-k3"), "x"));
        assertTrue(paid.getMessage().contains("pago"));
        verify(memory, never()).save(anyList(), anyString());
    }
}

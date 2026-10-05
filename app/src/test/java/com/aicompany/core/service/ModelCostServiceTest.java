package com.aicompany.core.service;

import com.aicompany.core.model.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Costo real por misión (2026-10-04): cada llamada remota con sus tokens y su costo, atribuida a su misión y tarea. */
class ModelCostServiceTest {

    private final ModelUsageMemoryService memory = mock(ModelUsageMemoryService.class);
    private final MockEnvironment env = new MockEnvironment()
            .withProperty("remote-models.providers.bedrock.price.input", "3")
            .withProperty("remote-models.providers.bedrock.price.cached", "0.30")
            .withProperty("remote-models.providers.bedrock.price.output", "15");
    private final ModelCostService service = new ModelCostService(memory, env);

    @Test
    void aPaidCallIsStoredWithItsCostAndItsMission() {
        ModelCallContext.with("M-9", "M-9-BACKEND", () ->
                service.record("bedrock", "global.moonshotai.kimi-k3", "backend", "DEVELOPMENT_TASK",
                        new TokenUsage(1_000_000, 0, 100_000)));

        verify(memory).save(eq("M-9"), eq("M-9-BACKEND"), eq("backend"), eq("DEVELOPMENT_TASK"), eq("bedrock"),
                eq("global.moonshotai.kimi-k3"), eq(new TokenUsage(1_000_000, 0, 100_000)), doubleThat(c -> Math.abs(c - 4.5) < 1e-9));
    }

    @Test
    void aFreeProviderStoresTokensAtZeroCostAndWithoutMission() {
        service.record("nvidia", "moonshotai/kimi-k3", "ceo", "CHAT", new TokenUsage(10, 0, 5));

        verify(memory).save(isNull(), isNull(), eq("ceo"), eq("CHAT"), eq("nvidia"), eq("moonshotai/kimi-k3"),
                eq(new TokenUsage(10, 0, 5)), eq(0.0));
    }

    @Test
    void recordingNeverBreaksTheCall() {
        doThrow(new RuntimeException("neo4j caído")).when(memory).save(any(), any(), any(), any(), any(), any(), any(), anyDouble());
        assertDoesNotThrow(() -> service.record("bedrock", "m", "qa", "X", new TokenUsage(1, 0, 1)));
    }

    @Test
    void theMissionSummaryAddsUpByModel() {
        when(memory.byMission("M-9")).thenReturn(List.of(
                new ModelUsageMemoryService.Row("bedrock", "global.moonshotai.kimi-k3", 3, 900_000, 0, 60_000, 3.6),
                new ModelUsageMemoryService.Row("nvidia", "moonshotai/kimi-k3", 17, 2_000_000, 0, 200_000, 0.0)));

        var summary = service.missionCost("M-9");

        assertEquals(3.6, summary.totalUsd(), 1e-9);
        assertEquals(20, summary.calls());
        assertTrue(ModelCostService.describe(summary).contains("US$ 3.60"), ModelCostService.describe(summary));
        assertTrue(ModelCostService.describe(summary).contains("bedrock:global.moonshotai.kimi-k3: 3 llamadas"),
                ModelCostService.describe(summary));
    }
}

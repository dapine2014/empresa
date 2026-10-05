package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Costo real por misión (2026-10-04): tokens del "usage" de la API compatible con OpenAI (verificado en Bedrock). */
class TokenUsageTest {

    @Test
    void readsPromptCachedAndCompletionTokens() {
        var usage = TokenUsage.fromOpenAi(Map.of("prompt_tokens", 162, "completion_tokens", 70,
                "prompt_tokens_details", Map.of("cached_tokens", 40)));
        assertEquals(new TokenUsage(162, 40, 70), usage);
    }

    @Test
    void aMissingUsageIsZero() {
        assertEquals(TokenUsage.ZERO, TokenUsage.fromOpenAi(null));
        assertEquals(new TokenUsage(10, 0, 5), TokenUsage.fromOpenAi(Map.of("prompt_tokens", 10, "completion_tokens", 5)));
    }

    @Test
    void theCostChargesCachedTokensAtTheCachedPrice() {
        // Bedrock kimi-k3: US$ 3 entrada, 0,30 caché, 15 salida por millón.
        var cost = new TokenUsage(1_000_000, 200_000, 100_000).costUsd(new TokenUsage.Price(3.0, 0.30, 15.0));
        assertEquals(2.4 + 0.06 + 1.5, cost, 1e-9);
        assertEquals(0.0, new TokenUsage(5, 0, 5).costUsd(TokenUsage.Price.FREE), 1e-12);
    }
}

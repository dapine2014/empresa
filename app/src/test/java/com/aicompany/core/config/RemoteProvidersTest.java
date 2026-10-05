package com.aicompany.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Proveedores remotos (2026-10-04): cada uno con su URL, su key, el modelo con que se prueba la key y cómo apagar el razonamiento. */
class RemoteProvidersTest {

    @Test
    void nvidiaUsesTheDefaultUrlAndBedrockItsOwn() {
        var env = new MockEnvironment()
                .withProperty("remote-models.providers.bedrock.base-url", "https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1")
                .withProperty("remote-models.providers.bedrock.check-models", "global.moonshotai.kimi-k3")
                .withProperty("remote-models.providers.bedrock.thinking-off", "reasoning_effort=none")
                .withProperty("remote-models.providers.bedrock.api-key", "x");

        var providers = RemoteProviders.from(env, "https://integrate.api.nvidia.com/v1");

        // Decisión del fundador (2026-10-04): Alibaba se quitó.
        assertEquals(List.of("nvidia", "nvidia-discovery", "nvidia-creative", "nvidia-ceo", "bedrock"),
                providers.stream().map(RemoteProviders.Provider::name).toList());
        var nvidia = providers.get(0);
        assertEquals("https://integrate.api.nvidia.com/v1", nvidia.baseUrl());
        assertTrue(nvidia.nvidiaOptions());
        assertEquals("", nvidia.thinkingOff());
        var bedrock = providers.get(4);
        assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1", bedrock.baseUrl());
        assertFalse(bedrock.nvidiaOptions());
        assertEquals(List.of("global.moonshotai.kimi-k3"), bedrock.checkModels());
        assertEquals("reasoning_effort=none", bedrock.thinkingOff());
        assertEquals("x", bedrock.apiKey());
    }

    // Lentitud como caída (decisión del fundador 2026-10-05): NVIDIA gratis tiene su propio plazo por llamada (6 min);
    // al pasarlo la llamada se repite en Bedrock. Sin plazo propio, el general.
    @Test
    void eachProviderCanHaveItsOwnTotalTimeout() {
        var env = new MockEnvironment().withProperty("remote-models.providers.nvidia.total-timeout", "6m");

        var providers = RemoteProviders.from(env, "https://integrate.api.nvidia.com/v1");

        assertEquals(java.time.Duration.ofMinutes(6), providers.get(0).totalTimeout(java.time.Duration.ofMinutes(12)));
        assertEquals(java.time.Duration.ofMinutes(12), providers.get(4).totalTimeout(java.time.Duration.ofMinutes(12)));
    }
}

package com.aicompany.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Proveedores pagos (2026-10-04): cada proveedor remoto con su URL, su key y el modelo con que se prueba la key. */
class RemoteProvidersTest {

    @Test
    void nvidiaUsesTheDefaultUrlAndPaidProvidersTheirOwn() {
        var env = new MockEnvironment()
                .withProperty("remote-models.providers.alibaba.base-url", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
                .withProperty("remote-models.providers.alibaba.check-models", "qwen3-coder-plus")
                .withProperty("remote-models.providers.alibaba.api-key", "x");

        var providers = RemoteProviders.from(env, "https://integrate.api.nvidia.com/v1");

        assertEquals(List.of("nvidia", "nvidia-discovery", "nvidia-creative", "nvidia-ceo", "alibaba", "bedrock"),
                providers.stream().map(RemoteProviders.Provider::name).toList());
        var nvidia = providers.get(0);
        assertEquals("https://integrate.api.nvidia.com/v1", nvidia.baseUrl());
        assertTrue(nvidia.nvidiaOptions());
        var alibaba = providers.get(4);
        assertEquals("https://dashscope-intl.aliyuncs.com/compatible-mode/v1", alibaba.baseUrl());
        assertFalse(alibaba.nvidiaOptions());
        assertEquals(List.of("qwen3-coder-plus"), alibaba.checkModels());
        assertEquals("x", alibaba.apiKey());
    }
}

package com.aicompany.core.config;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;

/**
 * Proveedores remotos compatibles con OpenAI (2026-10-04): los 4 grupos de NVIDIA (decisión del fundador 2026-09-27)
 * más los pagos (Alibaba Model Studio, Amazon Bedrock), cada uno con su URL, su key (siembra del .env; la de Settings
 * gana) y los modelos con que se prueba una key nueva. Las opciones propias de NVIDIA solo van a NVIDIA.
 */
public final class RemoteProviders {

    public record Provider(String name, String baseUrl, String apiKey, List<String> checkModels, boolean nvidiaOptions) {
    }

    static final List<String> NAMES = List.of("nvidia", "nvidia-discovery", "nvidia-creative", "nvidia-ceo", "alibaba",
            "bedrock");

    private RemoteProviders() {
    }

    public static List<Provider> from(Environment env, String defaultBaseUrl) {
        return NAMES.stream().map(name -> {
            var prefix = "remote-models.providers." + name + ".";
            var baseUrl = env.getProperty(prefix + "base-url", defaultBaseUrl);
            var checks = Arrays.stream(env.getProperty(prefix + "check-models", "").split(","))
                    .map(String::strip).filter(m -> !m.isEmpty()).toList();
            return new Provider(name, baseUrl, env.getProperty(prefix + "api-key", ""), checks,
                    baseUrl.contains("nvidia.com"));
        }).toList();
    }
}

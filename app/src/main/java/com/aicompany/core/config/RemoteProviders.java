package com.aicompany.core.config;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;

/**
 * Proveedores remotos compatibles con OpenAI (2026-10-04): los 4 grupos de NVIDIA (decisión del fundador 2026-09-27)
 * más el pago (Amazon Bedrock, respaldo cuando NVIDIA cae), cada uno con su URL, su key (siembra del .env; la de Settings
 * gana) y los modelos con que se prueba una key nueva. Las opciones propias de NVIDIA solo van a NVIDIA.
 */
public final class RemoteProviders {

    /**
     * {@code thinkingOff}: "parámetro=valor" propio del proveedor que apaga el razonamiento (Bedrock
     * {@code reasoning_effort=none}, verificado en vivo 2026-10-04).
     */
    public record Provider(String name, String baseUrl, String apiKey, List<String> checkModels, boolean nvidiaOptions,
                           String thinkingOff, java.time.Duration ownTotalTimeout) {

        /** Lentitud como caída (decisión del fundador 2026-10-05): plazo propio del proveedor o, si no tiene, el general. */
        public java.time.Duration totalTimeout(java.time.Duration general) {
            return ownTotalTimeout == null ? general : ownTotalTimeout;
        }
    }

    // Decisión del fundador (2026-10-04): Alibaba se quitó (consola sin activación posible); el pago es Bedrock.
    static final List<String> NAMES = List.of("nvidia", "nvidia-discovery", "nvidia-creative", "nvidia-ceo", "bedrock");

    private RemoteProviders() {
    }

    public static List<Provider> from(Environment env, String defaultBaseUrl) {
        return NAMES.stream().map(name -> {
            var prefix = "remote-models.providers." + name + ".";
            var baseUrl = env.getProperty(prefix + "base-url", defaultBaseUrl);
            var checks = Arrays.stream(env.getProperty(prefix + "check-models", "").split(","))
                    .map(String::strip).filter(m -> !m.isEmpty()).toList();
            return new Provider(name, baseUrl, env.getProperty(prefix + "api-key", ""), checks,
                    baseUrl.contains("nvidia.com"), env.getProperty(prefix + "thinking-off", ""),
                    timeoutOf(env.getProperty(prefix + "total-timeout")));
        }).toList();
    }

    private static java.time.Duration timeoutOf(String value) {
        return value == null || value.isBlank() ? null
                : org.springframework.boot.convert.DurationStyle.detectAndParse(value.strip());
    }
}

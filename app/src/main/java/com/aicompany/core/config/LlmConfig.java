package com.aicompany.core.config;

import com.aicompany.core.llm.NvidiaNimLlmProvider;
import com.aicompany.core.llm.OllamaLlmProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selecciona qué {@code LlmProvider} usa el CEO
 * (`llm.ceo-provider=ollama|nvidia`, default `ollama` — ver
 * `docs/superpowers/specs/2026-09-18-nvidia-ceo-provider-design.md`).
 * Completado en la Task 5 de ese plan con el bean `NvidiaNimLlmProvider`
 * y el bean selector `ceoProvider`.
 */
@Configuration
public class LlmConfig {

    @Bean
    OllamaLlmProvider ollamaLlmProvider(
            RestClient ollama,
            @Value("${ollama.ceo-model}") String ceoModel) {

        return new OllamaLlmProvider(ollama, ceoModel);
    }

    @Bean
    NvidiaNimLlmProvider nvidiaNimLlmProvider(
            @Value("${nvidia.base-url}") String baseUrl,
            @Value("${nvidia.api-key}") String apiKey,
            @Value("${nvidia.ceo-model}") String model,
            @Value("${nvidia.max-tokens}") int maxTokens,
            JsonMapper jsonMapper) {

        return new NvidiaNimLlmProvider(baseUrl, apiKey, model, maxTokens, jsonMapper);
    }

    @Bean
    com.aicompany.core.llm.LlmProvider ceoProvider(
            @Value("${llm.ceo-provider}") String ceoProviderName,
            OllamaLlmProvider ollamaLlmProvider,
            NvidiaNimLlmProvider nvidiaNimLlmProvider) {

        return "nvidia".equalsIgnoreCase(ceoProviderName)
                ? nvidiaNimLlmProvider
                : ollamaLlmProvider;
    }
}

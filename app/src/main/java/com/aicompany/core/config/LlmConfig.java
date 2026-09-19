package com.aicompany.core.config;

import com.aicompany.core.llm.OllamaLlmProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

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
}

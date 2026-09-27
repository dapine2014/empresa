package com.aicompany.core.config;

import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class CoreConfig {
    @Bean
    Driver neo4jDriver(@Value("${neo4j.uri}") String uri, @Value("${neo4j.username}") String username, @Value("${neo4j.password}") String password) {
        return GraphDatabase.driver(uri, org.neo4j.driver.AuthTokens.basic(username, password));
    }

    @Bean
    RestClient ollamaClient(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.read-timeout:15m}") java.time.Duration readTimeout) {
        // Verificado en vivo: sin timeout, una generación en bucle bloqueó una misión más de una hora.
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Bean
    AppProperties appProperties(@Value("${company.name}") String name, @Value("${company.seed-capital-usd}") double seedCapitalUsd, @Value("${company.challenge-days}") int challengeDays) {
        return new AppProperties(name, seedCapitalUsd, challengeDays);
    }

    @Bean
    com.aicompany.core.service.SandboxRunnerClient sandboxRunnerClient(
            @Value("${sandbox.runner.url}") String url,
            @Value("${sandbox.runner.token:}") String token,
            @Value("${sandbox.runner.timeout:30m}") java.time.Duration timeout) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(timeout);
        return new com.aicompany.core.service.SandboxRunnerClient(
                RestClient.builder().baseUrl(url).requestFactory(factory).build(), token);
    }

    /**
     * Proveedores remotos compatibles con OpenAI (NVIDIA), uno por grupo de agentes con su propia key (decisión del
     * fundador 2026-09-27). Agent.model "<proveedor>:<modelo>" elige el cliente; sin key, falla solo al usarse.
     */
    @Bean
    java.util.Map<String, com.aicompany.core.service.OpenAiCompatibleClient> remoteModelClients(
            org.springframework.core.env.Environment env,
            @Value("${remote-models.base-url:https://integrate.api.nvidia.com/v1}") String baseUrl,
            @Value("${remote-models.read-timeout:10m}") java.time.Duration readTimeout) {
        var clients = new java.util.LinkedHashMap<String, com.aicompany.core.service.OpenAiCompatibleClient>();
        for (var provider : java.util.List.of("nvidia", "nvidia-discovery", "nvidia-creative", "nvidia-ceo")) {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(java.time.Duration.ofSeconds(15));
            factory.setReadTimeout(readTimeout);
            clients.put(provider, new com.aicompany.core.service.OpenAiCompatibleClient(
                    RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(),
                    env.getProperty("remote-models.providers." + provider + ".api-key", ""),
                    java.time.Duration.ofSeconds(10)));
        }
        return clients;
    }

    /** Vulnerabilidades conocidas (parte 3 del sandbox): https://api.osv.dev. */
    @Bean
    com.aicompany.core.service.OsvClient osvClient(
            @Value("${dependencies.osv-url:https://api.osv.dev}") String baseUrl) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofSeconds(20));
        return new com.aicompany.core.service.OsvClient(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build());
    }
}

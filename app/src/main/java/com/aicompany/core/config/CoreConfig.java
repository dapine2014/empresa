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

    /** API remota compatible con OpenAI (NVIDIA) para Agent.model "nvidia:<modelo>". Sin key, falla solo al usarse. */
    @Bean
    com.aicompany.core.service.OpenAiCompatibleClient remoteModelClient(
            @Value("${remote-models.base-url:https://integrate.api.nvidia.com/v1}") String baseUrl,
            @Value("${remote-models.api-key:}") String apiKey,
            @Value("${remote-models.read-timeout:10m}") java.time.Duration readTimeout) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(15));
        factory.setReadTimeout(readTimeout);
        return new com.aicompany.core.service.OpenAiCompatibleClient(
                RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(), apiKey,
                java.time.Duration.ofSeconds(10));
    }
}

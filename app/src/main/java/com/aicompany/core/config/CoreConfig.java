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
}

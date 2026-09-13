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
    RestClient ollamaClient(@Value("${ollama.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    AppProperties appProperties(@Value("${company.name}") String name, @Value("${company.seed-capital-usd}") double seedCapitalUsd, @Value("${company.challenge-days}") int challengeDays) {
        return new AppProperties(name, seedCapitalUsd, challengeDays);
    }
}

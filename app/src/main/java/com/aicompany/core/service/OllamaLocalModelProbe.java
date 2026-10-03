package com.aicompany.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Consulta /api/tags de Ollama (sin inferencia): qué modelos locales hay. Nunca lanza. */
@Component
public class OllamaLocalModelProbe implements LocalModelProbe {

    private final RestClient client;

    public OllamaLocalModelProbe(@Value("${ollama.base-url}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Set<String>> installedModels() {
        try {
            var body = client.get().uri("/api/tags").retrieve().body(Map.class);
            var names = new HashSet<String>();
            for (var model : (List<Map<String, Object>>) body.getOrDefault("models", List.of())) {
                var name = String.valueOf(model.get("name"));
                names.add(name);
                if (name.endsWith(":latest")) {
                    names.add(name.substring(0, name.length() - ":latest".length()));
                }
            }
            return Optional.of(names);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}

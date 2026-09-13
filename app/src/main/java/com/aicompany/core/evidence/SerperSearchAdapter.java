package com.aicompany.core.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Adaptador de {@link WebSearchPort} para Serper (google.serper.dev):
 * proxy de resultados reales de Google. Tier gratuito de 2.500 consultas
 * sin tarjeta de crédito (verificado en la documentación oficial al
 * elegir este proveedor — Brave Search API eliminó su tier gratis sin
 * tarjeta en febrero de 2026, y DuckDuckGo HTML/Lite bloquea con un
 * challenge anti-bot casi de inmediato; ver notas en `EvidenceAcquisitionService`
 * y `CLAUDE.md`).
 *
 * A diferencia de Brave (GET con query params), Serper usa POST con
 * cuerpo JSON: {@code POST https://google.serper.dev/search},
 * header {@code X-API-KEY}, body {@code {"q", "gl", "hl", "num"}},
 * respuesta con array {@code organic[]} ({@code title}/{@code link}/
 * {@code snippet}) — no {@code url}/{@code description} como Brave.
 */
@Component
public class SerperSearchAdapter implements WebSearchPort {

    private final RestClient client;
    private final JsonMapper jsonMapper;
    private final String apiKey;

    public SerperSearchAdapter(
            @Value("${evidence.web-search.base-url}") String baseUrl,
            @Value("${evidence.web-search.api-key}") String apiKey,
            JsonMapper jsonMapper) {

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .build();

        this.jsonMapper = jsonMapper;
        this.apiKey = apiKey;
    }

    @Override
    public List<WebSearchResult> search(
            String query,
            String country,
            String language,
            int limit) {

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query es obligatorio");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "EVIDENCE_WEB_SEARCH_API_KEY no está configurada");
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("q", query);
        body.put("num", Math.min(Math.max(limit, 1), 20));

        if (country != null && !country.isBlank()) {
            body.put("gl", country.toLowerCase(Locale.ROOT));
        }

        if (language != null && !language.isBlank()) {
            body.put("hl", language.toLowerCase(Locale.ROOT));
        }

        var response = client.post()
                .uri("/search")
                .header("X-API-KEY", apiKey)
                .body(body)
                .retrieve()
                .body(String.class);

        return parseResults(response);
    }

    /**
     * Sin modificador de acceso a propósito: permite que
     * {@code SerperSearchAdapterTest} verifique el parseo sin necesidad
     * de una API key ni de red real.
     */
    List<WebSearchResult> parseResults(String response) {

        try {
            JsonNode root = jsonMapper.readTree(response);
            JsonNode organic = root.path("organic");

            var output = new ArrayList<WebSearchResult>();

            if (!organic.isArray()) {
                return output;
            }

            for (JsonNode item : organic) {
                var title = item.path("title").asString(null);
                var url = item.path("link").asString(null);
                var snippet = item.path("snippet").asString(null);

                if (url != null && !url.isBlank()) {
                    output.add(new WebSearchResult(
                            title,
                            url,
                            snippet));
                }
            }

            return List.copyOf(output);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "No se pudo interpretar la respuesta del buscador", ex);
        }
    }
}

package com.aicompany.core.evidence;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class SerperSearchAdapterTest {

    private final SerperSearchAdapter adapter = new SerperSearchAdapter(
            "https://google.serper.dev",
            "fake-api-key",
            JsonMapper.builder().build()
    );

    @Test
    void rejectsBlankQuery() {
        assertThrows(IllegalArgumentException.class, () -> adapter.search("", "co", "es", 10));
        assertThrows(IllegalArgumentException.class, () -> adapter.search(null, "co", "es", 10));
    }

    @Test
    void rejectsMissingApiKey() {
        var withoutKey = new SerperSearchAdapter(
                "https://google.serper.dev", "", JsonMapper.builder().build()
        );

        var ex = assertThrows(IllegalStateException.class,
                () -> withoutKey.search("negocio rentable", "co", "es", 10));

        assertTrue(ex.getMessage().contains("EVIDENCE_WEB_SEARCH_API_KEY"));
    }

    @Test
    void parsesValidSerperStyleResponse() {
        // Forma documentada de la respuesta de Serper: array top-level
        // "organic" con title/link/snippet (no url/description como Brave).
        var json = """
                {
                  "searchParameters": {"q": "precios asesoría empresarial colombia"},
                  "organic": [
                    {
                      "title": "Precios de asesoría empresarial en Colombia",
                      "link": "https://example.com/precios",
                      "snippet": "Rango de precios para consultoría PyME.",
                      "position": 1
                    },
                    {
                      "title": "Sin link",
                      "snippet": "Este resultado no tiene link y debe ignorarse.",
                      "position": 2
                    }
                  ]
                }
                """;

        var results = adapter.parseResults(json);

        assertEquals(1, results.size());
        assertEquals("https://example.com/precios", results.get(0).url());
        assertEquals("Precios de asesoría empresarial en Colombia", results.get(0).title());
        assertEquals("Rango de precios para consultoría PyME.", results.get(0).description());
    }

    @Test
    void returnsEmptyListWhenNoOrganicArray() {
        var results = adapter.parseResults("{\"searchParameters\": {}}");

        assertTrue(results.isEmpty());
    }

    @Test
    void throwsControlledExceptionOnInvalidJson() {
        var ex = assertThrows(IllegalStateException.class,
                () -> adapter.parseResults("esto no es json"));

        assertTrue(ex.getMessage().contains("No se pudo interpretar"));
    }
}

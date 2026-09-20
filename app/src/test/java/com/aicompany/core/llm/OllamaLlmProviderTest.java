package com.aicompany.core.llm;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OllamaLlmProviderTest {

    private final OllamaLlmProvider provider =
            new OllamaLlmProvider(mock(RestClient.class), "qwen2.5-coder:14b");

    @Test
    void parsesContentAndToolCallsFromOllamaResponse() {
        var response = Map.<String, Object>of(
                "message", Map.of(
                        "content", "hola",
                        "tool_calls", List.of(Map.of(
                                "function", Map.of("name", "query_company_memory")
                        ))
                )
        );

        var result = provider.parseResponse(response);

        assertEquals("hola", result.content());
        assertEquals(1, result.toolCalls().size());
    }

    @Test
    void returnsPlaceholderWhenResponseIsNull() {
        var result = provider.parseResponse(null);

        assertEquals("Sin respuesta del modelo.", result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void returnsPlaceholderWhenMessageIsMissing() {
        var result = provider.parseResponse(Map.of());

        assertEquals("Sin respuesta del modelo.", result.content());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void returnsEmptyToolCallsWhenAbsent() {
        var response = Map.<String, Object>of(
                "message", Map.of("content", "sin herramientas")
        );

        var result = provider.parseResponse(response);

        assertEquals("sin herramientas", result.content());
        assertTrue(result.toolCalls().isEmpty());
    }
}

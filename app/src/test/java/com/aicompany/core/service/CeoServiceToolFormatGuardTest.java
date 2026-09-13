package com.aicompany.core.service;

import com.aicompany.core.evidence.EvidenceAcquisitionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Confirma que la protección contra combinar `format` y `tools` en la
 * misma llamada a Ollama realmente lanza — no solo que el código de
 * producción "no debería" combinarlos nunca. Ver la razón (reproducida en
 * vivo con qwen2.5-coder:7b y qwen3:8b) en el javadoc de
 * {@code CeoService.rejectFormatCombinedWithTools}.
 */
class CeoServiceToolFormatGuardTest {

    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            "qwen2.5-coder:14b",
            "qwen3:8b",
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class)
    );

    @Test
    void rejectsFormatCombinedWithNonEmptyTools() {

        var tools = List.<Map<String, Object>>of(Map.of("type", "function"));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> ceoService.rejectFormatCombinedWithTools("AGENT_TASK", Map.of("type", "object"), tools));

        assertTrue(ex.getMessage().contains("no pueden combinarse"));
    }

    @Test
    void allowsFormatWithoutTools() {
        assertDoesNotThrow(() ->
                ceoService.rejectFormatCombinedWithTools("AGENT_TASK", Map.of("type", "object"), null));
    }

    @Test
    void allowsFormatWithEmptyTools() {
        assertDoesNotThrow(() ->
                ceoService.rejectFormatCombinedWithTools("AGENT_TASK", Map.of("type", "object"), List.of()));
    }

    @Test
    void allowsToolsWithoutFormat() {
        var tools = List.<Map<String, Object>>of(Map.of("type", "function"));

        assertDoesNotThrow(() ->
                ceoService.rejectFormatCombinedWithTools("AGENT_TOOL_CALL", null, tools));
    }
}

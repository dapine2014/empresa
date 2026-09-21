package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Sin Ollama real: `RestClient` mockeado con RETURNS_DEEP_STUBS para que
 * la llamada HTTP encadenada (post().uri().body().retrieve().body(Map.class))
 * no lance NPE — por la limitación conocida de Mockito con deep stubs y
 * tipos genéricos, esa llamada devuelve null, cayendo en la rama existente
 * de "respuesta vacía" de CeoService.callModel(), cuyo contenido luego
 * falla al parsear como DevelopmentResult. Esto ejercita el camino real
 * de "respuesta no parseable" sin necesitar Ollama real.
 */
class CeoServiceDevelopmentTest {

    private final CeoService ceoService = new CeoService(
            mock(RestClient.class, Answers.RETURNS_DEEP_STUBS),
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );

    @Test
    void throwsWhenOllamaResponseCannotBeParsedAsADevelopmentResult() {
        assertThrows(
                IllegalStateException.class,
                () -> ceoService.generateDevelopmentArtifact("engineering", "prompt de prueba", "qwen3:8b")
        );
    }
}

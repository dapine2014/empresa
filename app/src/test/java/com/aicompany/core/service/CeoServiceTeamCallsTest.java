package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Sin Ollama real (mismo criterio que CeoServiceToolFormatGuardTest): solo
 * el camino de error — cualquier fallo de la llamada o del parseo se
 * normaliza a IllegalStateException, que es lo que reintentan
 * TeamWorkPlanner y DevelopmentRuntime.
 */
class CeoServiceTeamCallsTest {

    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );

    @Test
    void planTeamWorkFailsWithIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> ceoService.planTeamWork("engineering", "prompt", "", "qwen3:8b"));
    }

    @Test
    void generateDevelopmentArtifactFailsWithIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> ceoService.generateDevelopmentArtifact("backend", "prompt", "", "qwen3:8b"));
    }

    @Test
    void reviewStaticWorkspaceFailsWithIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> ceoService.reviewStaticWorkspace("qa", "prompt", "", "qwen3:8b"));
    }
}

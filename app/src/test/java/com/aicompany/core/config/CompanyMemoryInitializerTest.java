package com.aicompany.core.config;

import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.CompanyPolicyService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.PromptMemoryService;
import com.aicompany.core.service.TeamMemoryService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class CompanyMemoryInitializerTest {

    // Decisión del fundador (2026-10-03): al arrancar, todo agente sin suplente local recibe el suplente por defecto.
    @Test
    void everyAgentGetsALocalFallbackAtStartup() {
        var memory = mock(CompanyMemoryService.class);
        new CompanyMemoryInitializer(memory, mock(MissionMemoryService.class), mock(TeamMemoryService.class),
                mock(PromptMemoryService.class), mock(CompanyPolicyService.class)).initializeAfterReady();
        verify(memory).ensureLocalFallbacks();
    }
}

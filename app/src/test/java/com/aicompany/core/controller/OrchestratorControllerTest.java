package com.aicompany.core.controller;

import com.aicompany.core.service.ProductOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrchestratorControllerTest {

    private final ProductOrchestrator orchestrator = mock(ProductOrchestrator.class);
    private final OrchestratorController controller = new OrchestratorController(orchestrator);

    // Spec orquestador §4 (2026-09-28): la pantalla Productos muestra el ciclo en curso y sus pasos.
    @Test
    void returnsTheCurrentCycle() {
        var view = new ProductOrchestrator.OrchestratorView(null, List.of(), false);
        when(orchestrator.current()).thenReturn(view);

        assertSame(view, controller.current());
    }
}

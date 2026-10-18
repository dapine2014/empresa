package com.aicompany.core.controller;

import com.aicompany.core.service.MissionService;
import com.aicompany.core.service.ModelCostService;
import com.aicompany.core.service.ModelUsageMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Costo real por misión (2026-10-04): GET /missions/{id}/model-cost para la pantalla Missions. */
class MissionControllerCostTest {

    @Test
    void theModelCostCarriesTotalCallsAndRows() {
        var costs = mock(ModelCostService.class);
        var controller = new MissionController(mock(MissionService.class));
        controller.setModelCost(costs);
        var row = new ModelUsageMemoryService.Row("bedrock", "global.moonshotai.kimi-k3", 3, 900_000, 0, 60_000, 3.6);
        when(costs.missionCost("M-9")).thenReturn(new ModelCostService.Summary(List.of(row)));

        var view = controller.modelCost("M-9");

        assertEquals("M-9", view.missionId());
        assertEquals(3.6, view.totalUsd(), 1e-9);
        assertEquals(3, view.calls());
        assertEquals(List.of(row), view.rows());
    }
}

package com.aicompany.core.controller;

import com.aicompany.core.model.ModelHealth;
import com.aicompany.core.service.FreeModelChainService;
import com.aicompany.core.service.ModelHealthService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** Cadena de modelos gratis (2026-10-15): se lee con su estado y se guarda tal como la ordena el fundador. */
class FreeModelChainControllerTest {

    private final FreeModelChainService chain = mock(FreeModelChainService.class);
    private final ModelHealthService health = mock(ModelHealthService.class);
    private final FreeModelChainController controller = new FreeModelChainController(chain, health);

    @Test
    void theChainComesWithTheStatusOfEachModel() {
        var glm = new ModelHealth("nvidia:z-ai/glm-5.3", "DOWN", null, "timeout", List.of());
        when(chain.chain()).thenReturn(new FreeModelChainService.FreeModelChain(List.of(glm.model()), "2026-10-15T10:00:00Z", "human"));
        when(health.freeChainSnapshot()).thenReturn(List.of(glm));

        var view = controller.get();

        assertEquals(List.of(glm), view.models());
        assertEquals("human", view.updatedBy());
    }

    @Test
    void theFoundersOrderIsSavedAsTheHumanActor() {
        when(chain.chain()).thenReturn(new FreeModelChainService.FreeModelChain(List.of(), null, null));

        controller.update(Map.of("models", List.of("nvidia:z-ai/glm-5.3", "nvidia:moonshotai/kimi-k2.6")));

        verify(chain).update(List.of("nvidia:z-ai/glm-5.3", "nvidia:moonshotai/kimi-k2.6"), "human");
    }
}

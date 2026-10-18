package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelCallContextTest {

    @Test
    void theContextLivesOnlyInsideTheWorkAndIsRestored() {
        assertNull(ModelCallContext.current());
        var seen = ModelCallContext.with("M-1", "T-1", () -> ModelCallContext.current());
        assertEquals(new ModelCallContext.Context("M-1", "T-1"), seen);
        ModelCallContext.with("M-1", null, () -> {
            ModelCallContext.with("M-1", "T-2", () -> assertEquals("T-2", ModelCallContext.current().taskId()));
            assertNull(ModelCallContext.current().taskId());
        });
        assertNull(ModelCallContext.current());
    }
}

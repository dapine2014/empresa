package com.aicompany.core.controller;

import com.aicompany.core.service.ApiKeyService;
import com.aicompany.core.service.ApiKeyService.ApiKeyStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiKeyControllerTest {

    private final ApiKeyService service = mock(ApiKeyService.class);
    private final ApiKeyController controller = new ApiKeyController(service);

    // Keys editables desde Settings (2026-09-29): el Command Center actúa como el fundador.
    @Test
    void updatingAKeyIsDoneAsTheFounder() {
        var status = new ApiKeyStatus("nvidia-ceo", "…a3F9", "FOUNDER", "2026-09-29T12:00:00Z", "human", List.of("Alex"));
        when(service.update("nvidia-ceo", "nvapi-nueva", "human")).thenReturn(status);

        assertSame(status, controller.update("nvidia-ceo", Map.of("apiKey", "nvapi-nueva")));
    }

    @Test
    void listsTheProviders() {
        when(service.list()).thenReturn(List.of());

        assertEquals(List.of(), controller.list());
    }
}

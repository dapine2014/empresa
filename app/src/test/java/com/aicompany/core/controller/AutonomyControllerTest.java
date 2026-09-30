package com.aicompany.core.controller;

import com.aicompany.core.service.AutonomyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutonomyControllerTest {

    private final AutonomyService service = mock(AutonomyService.class);
    private final AutonomyController controller = new AutonomyController(service);
    private final AutonomyService.AutonomyView view = new AutonomyService.AutonomyView(
            new AutonomyService.Front(true, true, null), new AutonomyService.Front(false, false, null),
            new AutonomyService.Waiting(0, 0, 0));

    // Spec modo automático (2026-09-29): el Dashboard lee y cambia los interruptores.
    @Test
    void getReturnsTheView() {
        when(service.view()).thenReturn(view);

        assertSame(view, controller.view());
    }

    @Test
    void putAppliesTheCommandFromTheDashboard() {
        var command = new AutonomyService.AutonomyCommand(true, null);
        when(service.update(command, "el Dashboard")).thenReturn(view);

        assertSame(view, controller.update(command));
    }

    @Test
    void anEmptyCommandChangesNothing() {
        var command = new AutonomyService.AutonomyCommand(null, null);
        when(service.update(command, "el Dashboard")).thenReturn(view);

        assertSame(view, controller.update(command));
        verify(service).update(command, "el Dashboard");
    }
}

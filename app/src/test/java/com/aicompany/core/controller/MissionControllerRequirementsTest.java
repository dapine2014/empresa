package com.aicompany.core.controller;

import com.aicompany.core.model.RequirementsDecisionCommand;
import com.aicompany.core.model.RequirementsView;
import com.aicompany.core.service.MissionService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionControllerRequirementsTest {

    private final MissionService service = mock(MissionService.class);
    private final com.aicompany.core.recovery.MissionRecoveryService recoveryService =
            mock(com.aicompany.core.recovery.MissionRecoveryService.class);
    private final MissionController controller = new MissionController(service, recoveryService);
    private static final RequirementsView VIEW = new RequirementsView("M-9", "AWAITING_SPEC_APPROVAL", 1, "DRAFT", null,
            0, 2, null, "Roles: vendedor.");

    @Test
    void requirementsAreReadDecidedAndIncrementsBuilt() {
        when(service.requirements("M-9")).thenReturn(Optional.of(VIEW));
        when(service.requirements("M-0")).thenReturn(Optional.empty());
        var command = new RequirementsDecisionCommand(RequirementsDecisionCommand.RequirementsDecision.APPROVE, null);
        when(service.decideRequirements("M-9", command)).thenReturn(VIEW);
        when(service.buildIncrement("M-9", 2)).thenReturn(VIEW);

        assertEquals(VIEW, controller.requirements("M-9").getBody());
        assertEquals(404, controller.requirements("M-0").getStatusCode().value());
        assertEquals(VIEW, controller.decideRequirements("M-9", command));
        assertEquals(VIEW, controller.buildIncrement("M-9", 2));
    }

    @Test
    void resumeResumesAndReturnsTheMission() {
        var view = new com.aicompany.core.model.MissionResponse("M-9", com.aicompany.core.model.MissionStatus.INTERRUPTED,
                "TEST", 10, "x", "m", java.time.Instant.now(), null, "TEAM-DEVELOPMENT");
        when(service.status("M-9")).thenReturn(Optional.of(view));

        assertEquals(view, controller.resume("M-9").getBody());
        verify(recoveryService).resume("M-9");
    }
}

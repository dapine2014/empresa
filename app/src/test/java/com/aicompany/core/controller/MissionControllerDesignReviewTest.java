package com.aicompany.core.controller;

import com.aicompany.core.model.DesignDecision;
import com.aicompany.core.model.DesignDecisionCommand;
import com.aicompany.core.model.DesignReviewView;
import com.aicompany.core.recovery.MissionRecoveryService;
import com.aicompany.core.service.DesignDecisionService;
import com.aicompany.core.service.MissionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Spec 2026-11-01 diseño visual §14-15: los endpoints de la revisión de diseño; el controller decide como el fundador. */
class MissionControllerDesignReviewTest {

    private final DesignDecisionService decisions = mock(DesignDecisionService.class);
    private final MissionController controller = new MissionController(mock(MissionService.class),
            mock(MissionRecoveryService.class));

    {
        controller.setDesignDecisions(decisions);
    }

    @Test
    void theDecisionIsTakenAsTheFounder() {
        var view = new DesignReviewView("M-1", "BLOCKED", "DESIGN_MISMATCH: PANT-01", "FOUNDER", List.of());
        when(decisions.decide("M-1", "PANT-01", DesignDecision.ACCEPT, "ok", "founder")).thenReturn(view);

        var response = controller.designDecision("M-1", new DesignDecisionCommand("PANT-01", DesignDecision.ACCEPT, "ok"));

        assertSame(view, response.getBody());
        verify(decisions).decide("M-1", "PANT-01", DesignDecision.ACCEPT, "ok", "founder");
    }

    @Test
    void theReviewOfAnUnknownMissionIs404() {
        when(decisions.view("M-9")).thenReturn(Optional.empty());
        assertEquals(404, controller.designReview("M-9").getStatusCode().value());
    }
}

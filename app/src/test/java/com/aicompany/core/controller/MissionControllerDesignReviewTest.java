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
    private final MissionService missions = mock(MissionService.class);
    private final MissionController controller = new MissionController(missions,
            mock(MissionRecoveryService.class));

    {
        controller.setDesignDecisions(decisions);
    }

    @Test
    void theDecisionIsTakenAsTheFounder() {
        var view = new DesignReviewView("M-1", "BLOCKED", "DESIGN_MISMATCH: PANT-01", "FOUNDER", List.of(), java.util.Map.of());
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

    // Revisión R15: los PNG de la captura de la app, al lado de los de las maquetas.
    @Test
    void theAppCaptureImageIsServedAsPng() {
        when(missions.designAppImage("M-1", "PANT-01-phone.png"))
                .thenReturn(Optional.of(java.nio.file.Path.of("/x/PANT-01-phone.png")));

        var response = controller.designAppImage("M-1", "PANT-01-phone.png");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(org.springframework.http.MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        when(missions.designAppImage("M-1", "x.png")).thenReturn(Optional.empty());
        assertEquals(404, controller.designAppImage("M-1", "x.png").getStatusCode().value());
    }
}

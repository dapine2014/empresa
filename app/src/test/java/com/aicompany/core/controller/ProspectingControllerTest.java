package com.aicompany.core.controller;

import com.aicompany.core.prospecting.ProspectingMemoryService;
import com.aicompany.core.prospecting.ProspectingRun;
import com.aicompany.core.prospecting.ProspectingService;
import com.aicompany.core.prospecting.StrategyProposalService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProspectingControllerTest {

    private final ProspectingService prospecting = mock(ProspectingService.class);
    private final StrategyProposalService strategies = mock(StrategyProposalService.class);
    private final ProspectingMemoryService memory = mock(ProspectingMemoryService.class);
    private final ProspectingController controller = new ProspectingController(prospecting, strategies, memory);

    @Test
    void runNowDelegatesToTheService() {
        var run = new ProspectingRun("R1", "P1", "BASE-DIRECTORIES", "COMPLETED", 0, 0, List.of(), null, Instant.now(), Instant.now());
        when(prospecting.runNow()).thenReturn(run);

        assertSame(run, controller.runNow());
    }

    @Test
    void runsAreTheLastTwenty() {
        controller.runs();

        verify(memory).runs(20);
    }

    @Test
    void approveAndRejectDelegate() {
        controller.approve("S1");
        controller.reject("S2");

        verify(strategies).approve("S1");
        verify(strategies).reject("S2");
    }
}

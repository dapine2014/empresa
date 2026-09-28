package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskIdsTest {

    @Test
    void roundZeroKeepsTodaysIdsAndLaterRoundsGetASuffix() {
        assertEquals("MISSION-1-SALES", TaskIds.agentTask("MISSION-1", "sales", 0));
        assertEquals("MISSION-1-SALES-R1", TaskIds.agentTask("MISSION-1", "sales", 1));
        assertEquals("MISSION-1-ENGINEERING-PLAN", TaskIds.planTask("MISSION-1", "engineering", 0));
        assertEquals("MISSION-1-ENGINEERING-PLAN-R2", TaskIds.planTask("MISSION-1", "engineering", 2));
    }

    @Test
    void theRoundIsReadBackFromTheId() {
        assertEquals(0, TaskIds.roundOf("MISSION-1-SALES"));
        assertEquals(2, TaskIds.roundOf("MISSION-1-FRONTEND-UI-R2"));
        assertEquals(0, TaskIds.roundOf("MISSION-R2D2-SALES"));
    }
}

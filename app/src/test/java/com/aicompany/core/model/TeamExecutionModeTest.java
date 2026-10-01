package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeamExecutionModeTest {

    @Test
    void theDevelopmentGroupRunsInDevelopmentMode() {
        assertEquals(TeamExecutionMode.DEVELOPMENT, TeamExecutionMode.forTeamType("DEVELOPMENT"));
        assertEquals(TeamExecutionMode.ANALYSIS, TeamExecutionMode.forTeamType("MARKETING_GROWTH"));
    }
}

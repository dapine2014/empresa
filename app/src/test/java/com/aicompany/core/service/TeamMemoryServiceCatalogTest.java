package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeamMemoryServiceCatalogTest {

    @Test
    void developmentReplacesEngineering() {
        assertTrue(TeamMemoryService.KNOWN_TEAM_IDS.contains("TEAM-DEVELOPMENT"));
        assertFalse(TeamMemoryService.KNOWN_TEAM_IDS.contains("TEAM-ENGINEERING"));
        assertEquals("DEVELOPMENT", TeamMemoryService.teamType("TEAM-DEVELOPMENT").orElseThrow());
    }

    @Test
    void everyCapabilityIsAtomic() {
        for (var capability : TeamMemoryService.allCapabilities()) {
            assertFalse(capability.contains(",") || capability.contains(";") || capability.contains("/"),
                    "no atómica: " + capability);
            assertTrue(capability.strip().split("\\s+").length <= 3, "más de 3 palabras: " + capability);
        }
    }
}

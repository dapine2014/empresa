package com.aicompany.core.event;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Bitácora (spec 2026-10-17): el nombre mostrado de cada actor (lo puro; el resto toca Neo4j). */
class MissionEventLogTest {

    private final Map<String, String> names = Map.of("engineering", "Neo", "ceo", "CEO");

    @Test void nonRosterIdsAndTheCeoMissionUpdateShowAsForjai() {
        assertEquals("Forjai", MissionEventLog.displayName(names, "system", "EMPRESA_X"));
        assertEquals("Forjai", MissionEventLog.displayName(names, "human", "EMPRESA_X"));
        assertEquals("Forjai", MissionEventLog.displayName(names, "sandbox", "EMPRESA_SANDBOX_FINISHED"));
        assertEquals("Forjai", MissionEventLog.displayName(names, "ceo", "EMPRESA_MISSION_UPDATED"));
        assertEquals("CEO", MissionEventLog.displayName(names, "ceo", "EMPRESA_TASK_COMPLETED"));
        assertEquals("Neo", MissionEventLog.displayName(names, "engineering", "EMPRESA_TASK_COMPLETED"));
        assertEquals("iris", MissionEventLog.displayName(names, "iris", "EMPRESA_TASK_COMPLETED"));
        assertNull(MissionEventLog.displayName(names, null, "EMPRESA_X"));
    }
}

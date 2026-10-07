package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec Diego ambientes §1 / Review Focus 3: lectura de databaseApplies y respaldo a las propiedades viejas. */
class MissionMemoryServiceAppliesTest {

    @Test
    void readsTheJsonWhenPresent() {
        var json = "{\"a-test\":{\"connection\":\"a-test\",\"environment\":\"TEST\",\"applied\":[1,2],"
                + "\"failedVersion\":null,\"text\":\"ok\",\"state\":\"APPLIED\"},"
                + "\"a-prod\":{\"connection\":\"a-prod\",\"environment\":\"PRODUCTION\",\"applied\":[],"
                + "\"failedVersion\":3,\"text\":\"mal\",\"state\":\"FAILED\"}}";
        var r = MissionMemoryService.parseApplies(json, "vieja", List.of(9), null, "x");
        assertEquals(List.of("a-test", "a-prod"), List.copyOf(r.keySet()));
        assertEquals(List.of(1, 2), r.get("a-test").applied());
        assertEquals(3, r.get("a-prod").failedVersion());
        assertEquals("FAILED", r.get("a-prod").state());
    }

    @Test
    void fallsBackToTheLegacyProperties() {
        var r = MissionMemoryService.parseApplies(null, "citas", List.of(1, 2), null, "Base creada");
        assertEquals(1, r.size());
        var e = r.get("citas");
        assertEquals(List.of(1, 2), e.applied());
        assertEquals("Base creada", e.text());
        assertEquals("APPLIED", e.state());
        assertNull(e.environment());
        assertEquals("FAILED", MissionMemoryService.parseApplies(null, "c", List.of(), 2, "mal").get("c").state());
    }

    @Test
    void garbledJsonIsReportedNotSwallowed() {
        assertThrows(MissionMemoryService.UnreadableAppliesException.class,
                () -> MissionMemoryService.parseApplies("{no es json", "c", List.of(1), null, "x"));
    }

    @Test
    void nothingStoredIsEmpty() {
        assertTrue(MissionMemoryService.parseApplies(null, null, List.of(), null, null).isEmpty());
    }
}

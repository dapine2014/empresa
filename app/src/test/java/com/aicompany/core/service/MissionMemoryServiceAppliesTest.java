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

    private static MissionMemoryService withStoredJson(String json) {
        var driver = org.mockito.Mockito.mock(org.neo4j.driver.Driver.class);
        var session = org.mockito.Mockito.mock(org.neo4j.driver.Session.class);
        var result = org.mockito.Mockito.mock(org.neo4j.driver.Result.class);
        var row = org.mockito.Mockito.mock(org.neo4j.driver.Record.class);
        org.mockito.Mockito.when(driver.session()).thenReturn(session);
        org.mockito.Mockito.when(session.run(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>any())).thenReturn(result);
        org.mockito.Mockito.when(result.list()).thenReturn(List.of(row));
        org.mockito.Mockito.when(row.get("j")).thenReturn(org.neo4j.driver.Values.value(json));
        org.mockito.Mockito.when(row.get("c")).thenReturn(org.neo4j.driver.Values.value("vieja"));
        org.mockito.Mockito.when(row.get("a")).thenReturn(org.neo4j.driver.Values.value(List.of(1)));
        org.mockito.Mockito.when(row.get("f")).thenReturn(org.neo4j.driver.Values.NULL);
        org.mockito.Mockito.when(row.get("r")).thenReturn(org.neo4j.driver.Values.NULL);
        return new MissionMemoryService(driver);
    }

    // Revisión final Diego ambientes: appliedAnywhere es estricta; con el JSON ilegible lanza (quien llama falla cerrado).
    @Test
    void appliedAnywhereThrowsWhenTheJsonIsUnreadable() {
        var memory = withStoredJson("{no es json");
        assertThrows(RuntimeException.class, () -> memory.appliedAnywhere("M-1"));
        // La lectura para mostrar sigue usando las propiedades viejas.
        assertEquals(List.of(1), memory.databaseApplies("M-1").get("vieja").applied());
    }

    @Test
    void appliedAnywhereJoinsEveryEnvironment() {
        var memory = withStoredJson("{\"t\":{\"connection\":\"t\",\"environment\":\"TEST\",\"applied\":[1,2],"
                + "\"state\":\"APPLIED\"},\"p\":{\"connection\":\"p\",\"environment\":\"PRODUCTION\","
                + "\"applied\":[1],\"failedVersion\":2,\"state\":\"FAILED\"}}");
        assertEquals(java.util.Set.of(1, 2), memory.appliedAnywhere("M-1"));
    }
}

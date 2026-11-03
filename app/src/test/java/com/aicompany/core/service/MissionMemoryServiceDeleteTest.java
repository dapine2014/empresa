package com.aicompany.core.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Revisión final del diseño visual (I1): borrar una misión borra también su diseño visual y sus revisiones de diseño. */
class MissionMemoryServiceDeleteTest {

    @Test
    @SuppressWarnings("unchecked")
    void deletingAMissionAlsoDeletesItsVisualDesignAndDesignReviews() {
        var driver = mock(Driver.class);
        var session = mock(Session.class);
        var tx = mock(TransactionContext.class);
        var result = mock(Result.class, RETURNS_DEEP_STUBS);
        when(driver.session()).thenReturn(session);
        when(tx.run(anyString(), anyMap())).thenReturn(result);
        when(session.executeWrite(any(TransactionCallback.class)))
                .thenAnswer(inv -> inv.getArgument(0, TransactionCallback.class).execute(tx));

        new MissionMemoryService(driver).deleteMission("M-1");

        var cypher = ArgumentCaptor.forClass(String.class);
        verify(tx, atLeastOnce()).run(cypher.capture(), any(Map.class));
        var owned = cypher.getAllValues().stream().filter(c -> c.contains("HAS_INTERACTION_DESIGN")).findFirst().orElseThrow();
        assertTrue(owned.contains("HAS_VISUAL_DESIGN") && owned.contains("HAS_DESIGN_REVIEW"), owned);
        assertTrue(owned.contains("DETACH DELETE n"), owned);
    }
}

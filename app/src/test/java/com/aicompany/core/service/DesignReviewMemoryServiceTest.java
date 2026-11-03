package com.aicompany.core.service;

import com.aicompany.core.agent.validation.DesignComparison;
import com.aicompany.core.model.Box;
import com.aicompany.core.model.DesignScreenReview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Revisión R15: las diferencias por elemento de la comparación se guardan (JSON) y se leen igual para la vista. */
class DesignReviewMemoryServiceTest {

    @Test
    void theDifferencesRoundTripThroughTheStoredJson() {
        var result = new DesignComparison.ScreenResult("PANT-01", "phone", 0.6, List.of(new DesignComparison.ElementDiff(
                "btn", new Box("btn", 0, 0, 10, 10, null), new Box("btn", 0, 0, 30, 30, null),
                "tamaño maqueta 10x10 / app 30x30")), List.of("logo"), false);

        var json = DesignReviewMemoryService.differencesJson(result);

        assertEquals(List.of(new DesignScreenReview.Difference("btn", "tamaño maqueta 10x10 / app 30x30")),
                DesignReviewMemoryService.differences(json));
    }

    // Revisión final del diseño visual: la decisión toma la pantalla AWAITING_DECISION del incremento en curso
    // (coalesce(buildingIncrement, builtIncrement, 0), como RequirementsMemoryService.currentIncrement), no la de
    // número más alto.
    @Test
    @SuppressWarnings("unchecked")
    void decideClaimsTheScreenOfTheCurrentIncrement() {
        var driver = org.mockito.Mockito.mock(org.neo4j.driver.Driver.class);
        var session = org.mockito.Mockito.mock(org.neo4j.driver.Session.class);
        var tx = org.mockito.Mockito.mock(org.neo4j.driver.TransactionContext.class);
        var result = org.mockito.Mockito.mock(org.neo4j.driver.Result.class);
        org.mockito.Mockito.when(driver.session()).thenReturn(session);
        org.mockito.Mockito.when(tx.run(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(result);
        org.mockito.Mockito.when(result.list()).thenReturn(List.of());
        org.mockito.Mockito.when(session.executeWrite(org.mockito.ArgumentMatchers.any(org.neo4j.driver.TransactionCallback.class)))
                .thenAnswer(inv -> inv.getArgument(0, org.neo4j.driver.TransactionCallback.class).execute(tx));

        var decided = new DesignReviewMemoryService(driver).decide("M-1", "PANT-01", "ACCEPTED", "founder", "", 0);

        assertTrue(decided.isEmpty());
        var cypher = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(tx, org.mockito.Mockito.atLeastOnce()).run(cypher.capture(),
                org.mockito.ArgumentMatchers.anyMap());
        var claim = cypher.getAllValues().stream().filter(c -> c.contains("SET r.status = 'DECIDING'")).findFirst().orElseThrow();
        assertTrue(claim.contains("r.increment = coalesce(m.buildingIncrement, m.builtIncrement, 0)"), claim);
        assertFalse(claim.contains("ORDER BY r.increment DESC"), claim);
    }

    @Test
    void missingOrBrokenJsonReadsAsNoDifferences() {
        assertEquals(List.of(), DesignReviewMemoryService.differences(null));
        assertEquals(List.of(), DesignReviewMemoryService.differences(""));
        assertEquals(List.of(), DesignReviewMemoryService.differences("{no es json"));
        assertEquals("[]", DesignReviewMemoryService.differencesJson(
                new DesignComparison.ScreenResult("PANT-01", "phone", 1.0, null, List.of(), true)));
    }
}

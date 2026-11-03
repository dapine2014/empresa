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

    @Test
    void missingOrBrokenJsonReadsAsNoDifferences() {
        assertEquals(List.of(), DesignReviewMemoryService.differences(null));
        assertEquals(List.of(), DesignReviewMemoryService.differences(""));
        assertEquals(List.of(), DesignReviewMemoryService.differences("{no es json"));
        assertEquals("[]", DesignReviewMemoryService.differencesJson(
                new DesignComparison.ScreenResult("PANT-01", "phone", 1.0, null, List.of(), true)));
    }
}

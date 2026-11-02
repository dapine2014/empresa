package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.PolicyKey;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Spec 2026-11-01 front con diseño visual §12 y §15: las dos políticas del diseño y sus rangos. */
class CompanyPolicyServiceTest {

    private final Driver driver = mock(Driver.class);
    private final CompanyPolicyService service = new CompanyPolicyService(driver, new AppProperties("Forjai", 50, 60));

    @Test
    void theDesignPoliciesHaveTheirDefaults() {
        var defaults = CompanyPolicyService.defaults(new AppProperties("Forjai", 50, 60));
        assertEquals(0.0, defaults.get(PolicyKey.DESIGN_MISMATCH_DECIDER));
        assertEquals(0.90, defaults.get(PolicyKey.DESIGN_SIMILARITY_THRESHOLD));
    }

    @Test
    void productOwnerDeciderRejectedForNow() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.createVersion(PolicyKey.DESIGN_MISMATCH_DECIDER, 1, "que decida el PO"));
        assertEquals("El PO decidirá las diferencias de diseño cuando se activen los demás modelos; por ahora solo "
                + "FOUNDER (0).", ex.getMessage());
        verifyNoInteractions(driver);
        // FOUNDER (0) sí es un valor válido, aunque las demás políticas exijan un valor positivo.
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.DESIGN_MISMATCH_DECIDER, 0));
    }

    @Test
    void thresholdOutOfRangeRejected() {
        for (var value : new double[]{0.49, 1.01, 0, -1}) {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> service.createVersion(PolicyKey.DESIGN_SIMILARITY_THRESHOLD, value, "calibración"));
            assertTrue(ex.getMessage().contains("0,5") && ex.getMessage().contains("1,0"), ex.getMessage());
        }
        verifyNoInteractions(driver);
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.DESIGN_SIMILARITY_THRESHOLD, 0.5));
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.DESIGN_SIMILARITY_THRESHOLD, 0.85));
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.DESIGN_SIMILARITY_THRESHOLD, 1.0));
    }
}

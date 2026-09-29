package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.OrchestratorStatus;
import com.aicompany.core.model.PolicyKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Spec orquestador (2026-09-28): encendido por defecto, uno a la vez; ambos editables en Settings. */
class CompanyPolicyDefaultsTest {

    @Test
    void theOrchestratorIsOnAndOneAtATimeByDefault() {
        var defaults = CompanyPolicyService.defaults(new AppProperties("Forjai", 50, 60));

        assertEquals(1.0, defaults.get(PolicyKey.ORCHESTRATOR_ENABLED));
        assertEquals(1.0, defaults.get(PolicyKey.MAX_AUTONOMOUS_PRODUCTS));
        assertEquals(PolicyKey.values().length, defaults.size(), "toda policy del catálogo tiene default");
    }

    @Test
    void onlyTheFirstFourStatusesAreActive() {
        assertEquals(4, Arrays.stream(OrchestratorStatus.values()).filter(OrchestratorStatus::active).count());
        assertTrue(OrchestratorStatus.BUILDING.active());
        assertFalse(OrchestratorStatus.READY.active());
    }
}

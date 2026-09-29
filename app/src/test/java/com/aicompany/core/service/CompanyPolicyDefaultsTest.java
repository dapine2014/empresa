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

    // Revisión en vivo (2026-09-29): "pausa el orquestador" fallaba porque toda policy exigía un valor > 0.
    @Test
    void theOrchestratorSwitchAcceptsZeroAndOneOnly() {
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.ORCHESTRATOR_ENABLED, 0));
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.ORCHESTRATOR_ENABLED, 1));
        assertThrows(IllegalArgumentException.class, () -> CompanyPolicyService.validateValue(PolicyKey.ORCHESTRATOR_ENABLED, 0.5));
        assertThrows(IllegalArgumentException.class, () -> CompanyPolicyService.validateValue(PolicyKey.ORCHESTRATOR_ENABLED, 2));
    }

    @Test
    void theOtherPoliciesStillMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> CompanyPolicyService.validateValue(PolicyKey.SEED_CAPITAL_USD, 0));
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.SEED_CAPITAL_USD, 50));
    }
}

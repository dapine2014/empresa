package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Decisión del fundador (2026-10-16): un intento extra con el respaldo pago tras respuestas inválidas. */
class PaidRetryTest {

    private static final String PAID = "bedrock:global.moonshotai.kimi-k3";
    private final CompanyMemoryService company = mock(CompanyMemoryService.class);
    private final ModelHealthService health = mock(ModelHealthService.class);

    @Test void emptyWithoutPaidFallback() {
        when(company.agentPaidFallbackModel("a")).thenReturn("");
        assertEquals(Optional.empty(), PaidRetry.modelFor("a", "nvidia:x", false, company, health));
        when(company.agentPaidFallbackModel("a")).thenReturn(null);
        assertEquals(Optional.empty(), PaidRetry.modelFor("a", "nvidia:x", false, company, health));
    }

    @Test void emptyWhenPaidIsTheUsedModel() {
        when(company.agentPaidFallbackModel("a")).thenReturn(PAID);
        assertEquals(Optional.empty(), PaidRetry.modelFor("a", PAID, false, company, health));
    }

    @Test void emptyWhenTheUsedModelWasDown() {
        when(company.agentPaidFallbackModel("a")).thenReturn(PAID);
        assertEquals(Optional.empty(), PaidRetry.modelFor("a", "nvidia:x", true, company, health));
    }

    @Test void emptyWhenPaidIsDown() {
        when(company.agentPaidFallbackModel("a")).thenReturn(PAID);
        when(health.isDown(PAID)).thenReturn(true);
        assertEquals(Optional.empty(), PaidRetry.modelFor("a", "nvidia:x", false, company, health));
    }

    @Test void presentInTheNormalCase() {
        when(company.agentPaidFallbackModel("a")).thenReturn(" " + PAID + " ");
        assertEquals(Optional.of(PAID), PaidRetry.modelFor("a", "nvidia:x", false, company, health));
        assertEquals(Optional.of(PAID), PaidRetry.modelFor("a", "nvidia:x", false, company, null));
    }

    @Test void theTrackerGivesOneExtraAttemptOnlyAndNeverAfterACancellation() {
        when(company.agentPaidFallbackModel("a")).thenReturn(PAID);
        when(health.isDown("nvidia:x")).thenReturn(true);
        var down = PaidRetry.start("a", "nvidia:x", company, health);
        when(health.isDown("nvidia:x")).thenReturn(false);
        assertEquals(Optional.empty(), down.next("op"), "el usado estaba DOWN al empezar");

        var retry = PaidRetry.start("a", "nvidia:x", company, health);
        assertEquals("", retry.suffix());
        assertEquals(Optional.of(PAID), retry.next("op"));
        assertEquals(PAID, retry.used());
        assertEquals(" (y uno con el respaldo pago " + PAID + ")", retry.suffix());
        assertEquals(Optional.empty(), retry.next("op"), "un solo intento extra");

        var cancelled = PaidRetry.start("a", "nvidia:x", company, health);
        cancelled.note(new MissionCancellations.MissionCancelledException("M-1"));
        assertEquals(Optional.empty(), cancelled.next("op"));
    }
}

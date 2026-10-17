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
        assertEquals(Optional.empty(), PaidRetry.modelFor("a", "nvidia:x", company, health));
        when(company.agentPaidFallbackModel("a")).thenReturn(null);
        assertEquals(Optional.empty(), PaidRetry.modelFor("a", "nvidia:x", company, health));
    }

    @Test void emptyWhenPaidIsTheUsedModel() {
        when(company.agentPaidFallbackModel("a")).thenReturn(PAID);
        assertEquals(Optional.empty(), PaidRetry.modelFor("a", PAID, company, health));
    }

    @Test void theAgentsModelDownButAFreeAlternativeAnsweringStillGetsThePaidAttempt() {
        when(company.agentPaidFallbackModel("a")).thenReturn(PAID);
        when(health.isDown("nvidia:kimi")).thenReturn(true);
        when(health.effectiveModel("a", "nvidia:kimi")).thenReturn("nvidia:nemotron");
        var retry = PaidRetry.start("a", "nvidia:kimi", company, health);
        assertEquals(Optional.of(PAID), retry.next("op"));
    }

    @Test void emptyWhenTheAttemptsAlreadyRanOnThePaidFallback() {
        when(company.agentPaidFallbackModel("a")).thenReturn(PAID);
        when(health.isDown("nvidia:kimi")).thenReturn(true);
        when(health.effectiveModel("a", "nvidia:kimi")).thenReturn(PAID);
        assertEquals(Optional.empty(), PaidRetry.start("a", "nvidia:kimi", company, health).next("op"));
    }

    @Test void emptyWhenPaidIsDown() {
        when(company.agentPaidFallbackModel("a")).thenReturn(PAID);
        when(health.isDown(PAID)).thenReturn(true);
        assertEquals(Optional.empty(), PaidRetry.modelFor("a", "nvidia:x", company, health));
    }

    @Test void presentInTheNormalCase() {
        when(company.agentPaidFallbackModel("a")).thenReturn(" " + PAID + " ");
        assertEquals(Optional.of(PAID), PaidRetry.modelFor("a", "nvidia:x", company, health));
        assertEquals(Optional.of(PAID), PaidRetry.modelFor("a", "nvidia:x", company, null));
    }

    @Test void theTrackerGivesOneExtraAttemptOnlyAndNeverAfterACancellation() {
        when(company.agentPaidFallbackModel("a")).thenReturn(PAID);
        when(health.effectiveModel("a", "nvidia:x")).thenReturn(PAID);
        var onPaid = PaidRetry.start("a", "nvidia:x", company, health);
        when(health.effectiveModel("a", "nvidia:x")).thenReturn("nvidia:x");
        assertEquals(Optional.empty(), onPaid.next("op"), "los intentos ya corrieron en el respaldo al empezar");

        var retry = PaidRetry.start("a", "nvidia:x", company, health);
        assertEquals("", retry.suffix());
        assertEquals(Optional.of(PAID), retry.next("op"));
        assertEquals(PAID, retry.used());
        assertEquals(" (y uno con el respaldo pago " + PAID + ")", retry.suffix());
        assertEquals(Optional.empty(), retry.next("op"), "un solo intento extra");

        var cancelled = PaidRetry.start("a", "nvidia:x", company, health);
        cancelled.note(new MissionCancellations.MissionCancelledException("M-1"));
        assertEquals(Optional.empty(), cancelled.next("op"));

        var wrapped = PaidRetry.start("a", "nvidia:x", company, health);
        wrapped.note(new IllegalStateException("no devolvió un JSON válido",
                new RuntimeException("x", new MissionCancellations.MissionCancelledException("M-1"))));
        assertEquals(Optional.empty(), wrapped.next("op"));
    }
}

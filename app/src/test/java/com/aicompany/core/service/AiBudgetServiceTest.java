package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reloj fake mutable, deliberadamente no {@code Clock.fixed(...)} —
 * `resetsWhenCalendarDateChanges` necesita avanzar el reloj sobre la
 * MISMA instancia de {@code AiBudgetService} para probar el reset por
 * cambio de fecha (ver spec: "inyectando un reloj fake, no
 * Instant.now() real").
 */
class AiBudgetServiceTest {

    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void isNotExhaustedBelowLimit() {
        var clock = new MutableClock(Instant.parse("2026-09-19T10:00:00Z"));
        var budget = new AiBudgetService(clock, 3);

        budget.recordCall();
        budget.recordCall();

        assertFalse(budget.isExhausted());
    }

    @Test
    void isExhaustedAtLimit() {
        var clock = new MutableClock(Instant.parse("2026-09-19T10:00:00Z"));
        var budget = new AiBudgetService(clock, 2);

        budget.recordCall();
        budget.recordCall();

        assertTrue(budget.isExhausted());
    }

    @Test
    void resetsWhenCalendarDateChanges() {
        var clock = new MutableClock(Instant.parse("2026-09-19T23:59:00Z"));
        var budget = new AiBudgetService(clock, 1);

        budget.recordCall();
        assertTrue(budget.isExhausted());

        clock.advance(Instant.parse("2026-09-20T00:01:00Z"));

        assertFalse(budget.isExhausted());
    }

    @Test
    void startsUnexhaustedWithZeroCalls() {
        var clock = new MutableClock(Instant.parse("2026-09-19T10:00:00Z"));
        var budget = new AiBudgetService(clock, 5);

        assertFalse(budget.isExhausted());
    }

    /**
     * `dailyLimit=0` es un kill-switch real y útil (`NVIDIA_DAILY_CALL_LIMIT=0`
     * fuerza siempre el fallback a Ollama) — sin ninguna llamada registrada
     * todavía, `0 >= 0` ya cuenta como agotado.
     */
    @Test
    void isExhaustedImmediatelyWhenDailyLimitIsZero() {
        var clock = new MutableClock(Instant.parse("2026-09-19T10:00:00Z"));
        var budget = new AiBudgetService(clock, 0);

        assertTrue(budget.isExhausted());
    }
}

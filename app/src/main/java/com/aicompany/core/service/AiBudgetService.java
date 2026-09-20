package com.aicompany.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tope de cortesía sobre el tier gratuito de evaluación de NVIDIA NIM —
 * ver "Presupuesto diario simple, en memoria" en
 * `docs/superpowers/specs/2026-09-18-nvidia-ceo-provider-design.md`. No
 * persiste entre restarts a propósito (mismo criterio ya documentado en
 * `CLAUDE.md` para `Agent.status`/`MissionExecutor`): es un límite de
 * cortesía, no un control de gasto real, ya que este tier no cobra nada.
 * Solo lo consulta/incrementa {@code CeoService.callCeo} cuando el
 * proveedor configurado del CEO es NVIDIA — nunca gatea Ollama.
 */
@Service
public class AiBudgetService {

    private final Clock clock;
    private final int dailyLimit;
    private final AtomicInteger callsToday = new AtomicInteger(0);
    private volatile LocalDate resetDate;

    public AiBudgetService(
            Clock clock,
            @Value("${company.nvidia-daily-call-limit}") int dailyLimit) {

        this.clock = clock;
        this.dailyLimit = dailyLimit;
        this.resetDate = LocalDate.now(clock);
    }

    public synchronized boolean isExhausted() {
        resetIfNewDay();
        return callsToday.get() >= dailyLimit;
    }

    public synchronized void recordCall() {
        resetIfNewDay();
        callsToday.incrementAndGet();
    }

    private void resetIfNewDay() {

        var today = LocalDate.now(clock);

        if (!today.equals(resetDate)) {
            resetDate = today;
            callsToday.set(0);
        }
    }
}

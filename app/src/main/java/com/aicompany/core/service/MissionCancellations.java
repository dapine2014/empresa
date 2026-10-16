package com.aicompany.core.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cancelar una misión en curso (decisión del fundador 2026-10-15). El estado CANCELLED queda en Neo4j; este registro en
 * memoria corta el trabajo que sigue vivo en los hilos: la siguiente llamada al modelo de esa misión lanza
 * {@link MissionCancelledException} y MissionExecutor deja de avanzarla. La llamada o el job del sandbox que ya estaban en
 * curso terminan solos. Tras un reinicio no hace falta: la recuperación nunca retoma una misión CANCELLED.
 */
@Component
public class MissionCancellations {

    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    public void cancel(String missionId) {
        cancelled.add(missionId);
    }

    public boolean isCancelled(String missionId) {
        return missionId != null && cancelled.contains(missionId);
    }

    /** Lanza si la misión del hilo actual fue cancelada. */
    public void checkCurrent() {
        var context = ModelCallContext.current();
        if (context != null && isCancelled(context.missionId())) {
            throw new MissionCancelledException(context.missionId());
        }
    }

    /**
     * La cancelación en un error, aunque llegue envuelta (CeoService envuelve cualquier error en «no devolvió un JSON
     * válido»): recorre la cadena de causas.
     */
    public static java.util.Optional<MissionCancelledException> cancellationIn(Throwable error) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (var t = error; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof MissionCancelledException cancelled) {
                return java.util.Optional.of(cancelled);
            }
        }
        return java.util.Optional.empty();
    }

    public static class MissionCancelledException extends IllegalStateException {
        public MissionCancelledException(String missionId) {
            super("La misión " + missionId + " fue cancelada por el fundador.");
        }
    }
}

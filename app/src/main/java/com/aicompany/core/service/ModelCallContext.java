package com.aicompany.core.service;

import java.util.function.Supplier;

/**
 * Costo real por misión (2026-10-04): a qué misión y tarea pertenece una llamada al modelo. Se fija donde el trabajo
 * pasa a su hilo (MissionExecutor, AgentRuntime, DevelopmentRuntime) y CeoService la lee al registrar el uso.
 */
public final class ModelCallContext {

    public record Context(String missionId, String taskId) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private ModelCallContext() {
    }

    public static Context current() {
        return CURRENT.get();
    }

    public static <T> T with(String missionId, String taskId, Supplier<T> work) {
        var previous = CURRENT.get();
        CURRENT.set(new Context(missionId, taskId));
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void with(String missionId, String taskId, Runnable work) {
        with(missionId, taskId, () -> {
            work.run();
            return null;
        });
    }
}

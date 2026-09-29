package com.aicompany.core.model;

import java.time.Instant;

/** Historial inmutable del ciclo. */
public record OrchestratorStep(Instant at, String step, String detail) {
}

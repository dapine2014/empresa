package com.aicompany.core.model;

import java.time.Instant;
import java.util.List;

/** Estado de un modelo remoto (spec salud de modelos 2026-09-28): UP o DOWN desde since, y quién lo usa. */
public record ModelHealth(String model, String status, Instant since, String lastError, List<String> affectedAgents) {
}

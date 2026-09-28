package com.aicompany.core.model;

import java.time.Instant;

/** Cliente real (quien compra); nunca un LEAD de los agentes. */
public record FinanceCustomer(String id, String name, String contact, String missionId, Instant recordedAt) {
}
